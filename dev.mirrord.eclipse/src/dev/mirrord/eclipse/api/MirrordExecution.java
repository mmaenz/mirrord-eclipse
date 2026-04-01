package dev.mirrord.eclipse.api;

import dev.mirrord.eclipse.util.SimpleJsonParser;

import java.util.List;
import java.util.Map;

/**
 * The result of a successful {@code mirrord ext} run.
 *
 * Mirrors the Rust {@code MirrordExecution} struct that mirrord sends as the final
 * {@code FinishedTask} message payload.
 *
 * <pre>
 * {
 *   "environment":   {"MIRRORD_CONNECT_TLS":"true", ...},
 *   "patched_path":  "/tmp/mirrord/patched_java"  (or null),
 *   "env_to_unset":  ["LD_PRELOAD"],
 *   "uses_operator": false
 * }
 * </pre>
 */
public class MirrordExecution {

    /**
     * Environment variables to inject into the target process.
     * Keys are variable names, values are variable values.
     */
    public final Map<String, String> env;

    /**
     * On macOS with SIP-protected executables, mirrord creates a patched copy of the
     * executable and returns its path here. {@code null} on Linux or when no patching is needed.
     */
    public final String patchedPath;

    /** Environment variable names that must be removed from the target process environment. */
    public final List<String> envToUnset;

    /** Whether a mirrord Operator (Teams) was used for this session. */
    public final boolean usesOperator;

    public MirrordExecution(Map<String, String> env, String patchedPath,
                            List<String> envToUnset, boolean usesOperator) {
        this.env = env != null ? env : Map.of();
        this.patchedPath = patchedPath;
        this.envToUnset = envToUnset != null ? envToUnset : List.of();
        this.usesOperator = usesOperator;
    }

    @SuppressWarnings("unchecked")
    public static MirrordExecution fromJson(String json) {
        Map<String, Object> obj = SimpleJsonParser.parseObject(json);

        // environment: map of string→string
        Map<String, Object> rawEnv = SimpleJsonParser.getMap(obj, "environment");
        Map<String, String> env = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rawEnv.entrySet()) {
            if (e.getValue() instanceof String) {
                env.put(e.getKey(), (String) e.getValue());
            }
        }

        String patchedPath = SimpleJsonParser.getString(obj, "patched_path");

        List<String> envToUnset = SimpleJsonParser.getList(obj, "env_to_unset")
                .stream()
                .filter(o -> o instanceof String)
                .map(o -> (String) o)
                .toList();

        boolean usesOperator = SimpleJsonParser.getBoolean(obj, "uses_operator", false);

        return new MirrordExecution(env, patchedPath, envToUnset, usesOperator);
    }
}
