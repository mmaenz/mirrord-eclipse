package dev.mirrord.eclipse.api;

import dev.mirrord.eclipse.Activator;
import dev.mirrord.eclipse.MirrordPluginConstants;
import dev.mirrord.eclipse.util.SimpleJsonParser;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps all interactions with the mirrord CLI binary.
 *
 * <h3>CLI commands used</h3>
 * <ul>
 *   <li>{@code mirrord --version}             – get binary version</li>
 *   <li>{@code mirrord ls [-f config] [-n ns]} – list Kubernetes targets</li>
 *   <li>{@code mirrord verify-config --ide <path>} – validate config file</li>
 *   <li>{@code mirrord ext [-t target] [-f config] [-e executable]} – start agent & get env</li>
 * </ul>
 */
public class MirrordAPI {

    private final String cliPath;

    public MirrordAPI(String cliPath) {
        this.cliPath = cliPath;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Runs {@code mirrord --version} and returns the version string, e.g. {@code "3.68.0"}.
     */
    public String getBinaryVersion() throws CoreException {
        String stdout = exec(List.of("--version"), Map.of(), 5_000);
        // Output: "mirrord x.y.z\n"
        String[] parts = stdout.trim().split("\\s+");
        return parts.length >= 2 ? parts[1] : stdout.trim();
    }

    /**
     * Runs {@code mirrord ls} and parses the output into a {@link MirrordLsOutput}.
     *
     * @param configPath optional path to a mirrord config file
     * @param extraEnv   additional env vars (e.g. from the launch config)
     * @param targetTypes target type filter, e.g. {@code ["deployment","pod"]}
     * @param namespace  optional Kubernetes namespace override
     */
    public MirrordLsOutput listTargets(String configPath,
                                       Map<String, String> extraEnv,
                                       String[] targetTypes,
                                       String namespace) throws CoreException {
        List<String> args = new ArrayList<>(List.of("ls"));
        if (configPath != null && !configPath.isBlank()) {
            args.add("-f");
            args.add(configPath);
        }
        if (namespace != null && !namespace.isBlank()) {
            args.add("-n");
            args.add(namespace);
        }

        Map<String, String> env = new HashMap<>(extraEnv);
        // Tell mirrord which target types to include
        env.put(MirrordPluginConstants.ENV_LS_TARGET_TYPES,
                toJsonStringArray(targetTypes));

        String stdout = exec(args, env, 30_000);
        return MirrordLsOutput.fromJson(stdout.trim());
    }

    /**
     * Runs {@code mirrord verify-config --ide <path>} and returns the parsed result.
     *
     * @return {@code null} if {@code configPath} is null/blank (no config → skip verification)
     */
    public VerifiedConfig verifyConfig(String configPath) throws CoreException {
        if (configPath == null || configPath.isBlank()) return null;
        List<String> args = List.of("verify-config", "--ide", configPath);
        String stdout = exec(args, Map.of(), 15_000);
        return VerifiedConfig.fromJson(stdout.trim());
    }

    /**
     * Runs {@code mirrord ext} to connect an agent and retrieve execution parameters.
     *
     * <p>This is the core method. It streams JSONL progress messages from stdout and
     * reports them via {@code monitor.subTask()}. Resolves when mirrord sends the
     * {@code "mirrord preparing to launch"} {@code FinishedTask} message.</p>
     *
     * @param target      Kubernetes target path, e.g. {@code "deployment/my-app"},
     *                    or {@code null} for targetless mode
     * @param configPath  optional mirrord config file path
     * @param executable  optional path to the process executable (for macOS SIP patching)
     * @param extraEnv    env vars merged into the mirrord CLI environment
     * @param monitor     progress monitor (may be {@code null})
     */
    public MirrordExecution execute(String target,
                                    String configPath,
                                    String executable,
                                    Map<String, String> extraEnv,
                                    IProgressMonitor monitor) throws CoreException {
        List<String> args = new ArrayList<>();
        args.add("ext");

        if (target != null && !target.isBlank()) {
            args.add("-t");
            args.add(target);
        }
        if (configPath != null && !configPath.isBlank()) {
            args.add("-f");
            args.add(configPath);
        }
        if (executable != null && !executable.isBlank()) {
            args.add("-e");
            args.add(executable);
        }

        Activator.logger().info("mirrord ext args: " + args);

        ProcessBuilder pb = buildProcess(args, extraEnv);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new CoreException(new Status(IStatus.ERROR,
                    MirrordPluginConstants.PLUGIN_ID,
                    "Failed to start mirrord process: " + e.getMessage(), e));
        }

        // Read stderr in background thread
        StringBuilder stderrBuf = new StringBuilder();
        Thread stderrReader = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = err.readLine()) != null) {
                    stderrBuf.append(line).append('\n');
                    Activator.logger().info("[mirrord stderr] " + line);
                }
            } catch (IOException ignored) {}
        }, "mirrord-stderr-reader");
        stderrReader.setDaemon(true);
        stderrReader.start();

        // Read stdout (JSONL) synchronously in the calling thread
        try (BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {

            String line;
            while ((line = stdout.readLine()) != null) {
                if (line.isBlank()) continue;
                Activator.logger().info("[mirrord stdout] " + line);

                if (monitor != null && monitor.isCanceled()) {
                    process.destroyForcibly();
                    throw new CoreException(Status.CANCEL_STATUS);
                }

                MirrordExecution result = handleProgressLine(line, monitor);
                if (result != null) {
                    // "mirrord preparing to launch" FinishedTask received → success
                    process.destroyForcibly(); // clean up; process may already have exited
                    return result;
                }
            }
        } catch (IOException e) {
            throw new CoreException(new Status(IStatus.ERROR,
                    MirrordPluginConstants.PLUGIN_ID,
                    "Error reading mirrord output: " + e.getMessage(), e));
        }

        // Process ended without sending the FinishedTask message
        try { stderrReader.join(2_000); } catch (InterruptedException ignored) {}

        String stderr = stderrBuf.toString();
        String errorMsg = extractErrorMessage(stderr);
        throw new CoreException(new Status(IStatus.ERROR,
                MirrordPluginConstants.PLUGIN_ID,
                "mirrord exited unexpectedly. " +
                        (errorMsg != null ? errorMsg : "Check the Error Log for details.")));
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Parses one JSONL line from {@code mirrord ext} stdout.
     *
     * @return a {@link MirrordExecution} if this was the final success message, else {@code null}
     */
    private MirrordExecution handleProgressLine(String line, IProgressMonitor monitor) {
        Map<String, Object> msg;
        try {
            msg = SimpleJsonParser.parseObject(line);
        } catch (Exception e) {
            Activator.logger().info("Could not parse mirrord message: " + line);
            return null;
        }

        String type = SimpleJsonParser.getString(msg, "type");
        String name = SimpleJsonParser.getString(msg, "name");
        String message = SimpleJsonParser.getString(msg, "message");

        // Final success message
        if ("FinishedTask".equals(type) && "mirrord preparing to launch".equals(name)) {
            boolean success = SimpleJsonParser.getBoolean(msg, "success", false);
            if (success && message != null) {
                return MirrordExecution.fromJson(message);
            }
            return null;
        }

        // Progress / info / warning messages
        switch (type != null ? type : "") {
            case "Warning" -> {
                if (message != null && monitor != null) {
                    monitor.subTask("⚠ " + message);
                }
                Activator.logger().warn("[mirrord] " + message);
            }
            case "Info" -> {
                if (message != null && monitor != null) {
                    monitor.subTask(message);
                }
            }
            case "IdeMessage" -> {
                // Structured IDE message – just log for now; full dialog support can be added later
                Activator.logger().info("[mirrord IdeMessage] " + message);
            }
            default -> {
                // Generic progress message
                String progress = name != null ? name : "";
                if (message != null && !message.isBlank()) progress += ": " + message;
                if (!progress.isBlank() && monitor != null) {
                    monitor.subTask(progress);
                }
            }
        }
        return null;
    }

    /**
     * Runs a mirrord CLI command synchronously and returns stdout.
     *
     * @param timeoutMs maximum milliseconds to wait
     */
    private String exec(List<String> args, Map<String, String> extraEnv, long timeoutMs)
            throws CoreException {
        ProcessBuilder pb = buildProcess(args, extraEnv);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new CoreException(new Status(IStatus.ERROR,
                    MirrordPluginConstants.PLUGIN_ID,
                    "Failed to start mirrord: " + e.getMessage(), e));
        }

        StringBuilder stdoutBuf = new StringBuilder();
        StringBuilder stderrBuf = new StringBuilder();

        // Read both streams in background threads to avoid deadlock
        Thread outReader = readStreamInBackground(
                new BufferedReader(new InputStreamReader(process.getInputStream())),
                stdoutBuf, "mirrord-stdout");
        Thread errReader = readStreamInBackground(
                new BufferedReader(new InputStreamReader(process.getErrorStream())),
                stderrBuf, "mirrord-stderr");

        try {
            boolean finished = process.waitFor(timeoutMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new CoreException(new Status(IStatus.ERROR,
                        MirrordPluginConstants.PLUGIN_ID,
                        "mirrord command timed out after " + timeoutMs + " ms"));
            }
            outReader.join(2_000);
            errReader.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoreException(new Status(IStatus.ERROR,
                    MirrordPluginConstants.PLUGIN_ID, "Interrupted waiting for mirrord", e));
        }

        String stderr = stderrBuf.toString();
        String errorMsg = extractErrorMessage(stderr);
        if (errorMsg != null) {
            throw new CoreException(new Status(IStatus.ERROR,
                    MirrordPluginConstants.PLUGIN_ID, "mirrord error: " + errorMsg));
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new CoreException(new Status(IStatus.ERROR,
                    MirrordPluginConstants.PLUGIN_ID,
                    "mirrord exited with code " + exitCode +
                            (stderr.isBlank() ? "" : ": " + stderr.trim())));
        }

        return stdoutBuf.toString();
    }

    /** Builds a {@link ProcessBuilder} with the standard mirrord IDE environment. */
    private ProcessBuilder buildProcess(List<String> args, Map<String, String> extraEnv) {
        List<String> command = new ArrayList<>();
        command.add(cliPath);
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);

        // Start with the current JVM environment
        Map<String, String> env = pb.environment();

        // Layer in extra env from the launch config
        env.putAll(extraEnv);

        // Standard mirrord IDE env vars
        env.put(MirrordPluginConstants.ENV_PROGRESS_MODE, "json");
        env.put(MirrordPluginConstants.ENV_PROGRESS_SUPPORT_IDE, "true");
        env.put(MirrordPluginConstants.ENV_IDE_NAME, "eclipse");
        env.put(MirrordPluginConstants.ENV_LS_RICH_OUTPUT, "true");

        return pb;
    }

    private Thread readStreamInBackground(BufferedReader reader,
                                          StringBuilder buffer,
                                          String threadName) {
        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line).append('\n');
                }
            } catch (IOException ignored) {}
        }, threadName);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Attempts to parse a mirrord error from stderr.
     * mirrord writes errors as: {@code Error: {"message":"…","help":"…"}}
     */
    private String extractErrorMessage(String stderr) {
        if (stderr == null || stderr.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Error: (\\{.*\\})")
                .matcher(stderr);
        if (m.find()) {
            try {
                Map<String, Object> errObj = SimpleJsonParser.parseObject(m.group(1));
                String msg = SimpleJsonParser.getString(errObj, "message");
                String help = SimpleJsonParser.getString(errObj, "help");
                return help != null ? msg + " — " + help : msg;
            } catch (Exception ignored) {}
        }
        // Fall back to raw stderr if not JSON
        String trimmed = stderr.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Converts a String array to a JSON string array literal, e.g. {@code ["a","b"]}. */
    private String toJsonStringArray(String[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values[i]).append('"');
        }
        sb.append(']');
        return sb.toString();
    }
}
