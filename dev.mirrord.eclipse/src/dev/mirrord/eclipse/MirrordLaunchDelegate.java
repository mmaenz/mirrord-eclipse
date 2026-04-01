package dev.mirrord.eclipse;

import dev.mirrord.eclipse.api.MirrordAPI;
import dev.mirrord.eclipse.api.MirrordExecution;
import dev.mirrord.eclipse.api.MirrordLsOutput;
import dev.mirrord.eclipse.api.MirrordTarget;
import dev.mirrord.eclipse.api.VerifiedConfig;
import dev.mirrord.eclipse.binary.BinaryManager;
import dev.mirrord.eclipse.ui.TargetSelectionDialog;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchDelegate;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.ILaunchConfigurationDelegate;
import org.eclipse.debug.core.model.ILaunchConfigurationDelegate2;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Eclipse launch delegate for the {@code dev.mirrord.eclipse.launchConfigurationType} launch type.
 *
 * <h3>Launch flow</h3>
 * <ol>
 *   <li>Resolve the mirrord binary (download if needed)</li>
 *   <li>Validate the mirrord config file (if specified)</li>
 *   <li>Show target-selection dialog if no target is set in the config</li>
 *   <li>Run {@code mirrord ext} to start the cluster agent and retrieve env vars</li>
 *   <li>Inject mirrord env vars into a working copy of the wrapped launch config</li>
 *   <li>Hand off to the wrapped launch config's own delegate</li>
 * </ol>
 */
public class MirrordLaunchDelegate implements ILaunchConfigurationDelegate {

    @Override
    public void launch(ILaunchConfiguration configuration,
                       String mode,
                       ILaunch launch,
                       IProgressMonitor monitor) throws CoreException {

        SubMonitor sub = SubMonitor.convert(monitor, "Launching with mirrord", 100);

        // ------------------------------------------------------------------
        // 1. Find the wrapped launch configuration
        // ------------------------------------------------------------------
        String wrappedConfigName = configuration.getAttribute(
                MirrordPluginConstants.ATTR_WRAPPED_LAUNCH, "");
        if (wrappedConfigName.isBlank()) {
            throw error("No wrapped launch configuration selected. " +
                    "Open Run Configurations and select the configuration to wrap.");
        }

        ILaunchConfiguration wrapped = findLaunchConfig(wrappedConfigName);
        if (wrapped == null) {
            throw error("Wrapped launch configuration not found: '" + wrappedConfigName +
                    "'. Please update the mirrord launch configuration.");
        }

        // ------------------------------------------------------------------
        // 2. Resolve the mirrord binary
        // ------------------------------------------------------------------
        sub.subTask("Resolving mirrord binary…");
        String configuredBinary = configuration.getAttribute(
                MirrordPluginConstants.ATTR_BINARY_PATH, "");
        boolean autoUpdate = configuration.getAttribute(
                MirrordPluginConstants.ATTR_AUTO_UPDATE, true);

        String binaryPath = BinaryManager.getMirrordBinary(
                configuredBinary, autoUpdate, sub.split(15));

        Activator.logger().info("Using mirrord binary: " + binaryPath);

        // ------------------------------------------------------------------
        // 3. Collect env from the wrapped launch config
        // ------------------------------------------------------------------
        Map<String, String> wrappedEnv = wrapped.getAttribute(
                MirrordPluginConstants.ATTR_ENVIRONMENT_VARIABLES,
                Collections.<String, String>emptyMap());

        // ------------------------------------------------------------------
        // 4. Resolve mirrord config file path
        // ------------------------------------------------------------------
        final String configPath = configuration.getAttribute(
                MirrordPluginConstants.ATTR_MIRRORD_CONFIG_PATH, "");
        
        // ------------------------------------------------------------------
        // 5. Verify config and determine if target selection is needed
        // ------------------------------------------------------------------
        sub.subTask("Verifying mirrord configuration…");
        MirrordAPI api = new MirrordAPI(binaryPath);
        VerifiedConfig verifiedConfig = null;

        if (configPath != "") {
            verifiedConfig = api.verifyConfig(configPath);
            if (verifiedConfig != null && !verifiedConfig.isSuccess()) {
                String errors = String.join("\n", verifiedConfig.errors);
                throw error("mirrord config verification failed:\n" + errors);
            }
            if (verifiedConfig != null) {
                for (String w : verifiedConfig.warnings) {
                    Activator.logger().warn("[mirrord] " + w);
                }
            }
        }
        sub.worked(10);

        // ------------------------------------------------------------------
        // 6. Target selection (if target not in config)
        // ------------------------------------------------------------------
        boolean targetInConfig = verifiedConfig != null && verifiedConfig.targetSet;
        String selectedTarget = null;
        String selectedNamespace = null;

        if (!targetInConfig) {
            sub.subTask("Fetching available Kubernetes targets…");
            MirrordLsOutput lsOutput = api.listTargets(
                    configPath,
                    new HashMap<>(wrappedEnv),
                    MirrordPluginConstants.SUPPORTED_TARGET_TYPES,
                    null);
            sub.worked(10);

            // Show dialog on the UI thread; collect result via array trick
            final String[] dialogResult = {null, null}; // [0]=target, [1]=namespace
            PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
                Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
                TargetSelectionDialog dialog = new TargetSelectionDialog(shell, api, configPath, lsOutput);
                if (dialog.open() == Dialog.OK) {
                    dialogResult[0] = dialog.getSelectedTarget();   // "targetless" or a path
                    dialogResult[1] = dialog.getSelectedNamespace();
                }
            });

            if (dialogResult[0] == null) {
                // User cancelled the dialog → abort the launch
                throw new OperationCanceledException("Launch cancelled by user.");
            }

            if (!"targetless".equals(dialogResult[0])) {
                selectedTarget = dialogResult[0];
            }
            selectedNamespace = dialogResult[1];
        }
        sub.worked(5);

        // ------------------------------------------------------------------
        // 7. Build environment for the mirrord ext invocation
        // ------------------------------------------------------------------
        Map<String, String> extEnv = new HashMap<>(wrappedEnv);
        if (selectedNamespace != null && !selectedNamespace.isBlank()) {
            extEnv.put(MirrordPluginConstants.ENV_TARGET_NAMESPACE, selectedNamespace);
        }

        // Java-specific: tell mirrord to detect the JDWP debugger port
        String wrappedTypeName = wrapped.getType().getIdentifier();
        addLanguageSpecificEnv(extEnv, wrappedTypeName);

        // ------------------------------------------------------------------
        // 8. Run mirrord ext → obtain cluster agent env vars
        // ------------------------------------------------------------------
        sub.subTask("Starting mirrord agent in cluster…");
        MirrordExecution execution = api.execute(
                selectedTarget, configPath, null, extEnv, sub.split(50));
        Activator.logger().info("mirrord execution received; env vars: " + execution.env.size());

        if (execution.patchedPath != null) {
            Activator.logger().warn(
                    "mirrord returned a patchedPath for SIP bypass (" + execution.patchedPath +
                            "). Automatic SIP patching for Eclipse JVM launches is not yet " +
                            "implemented; the launch may fail on macOS with SIP-protected JVMs.");
        }

        // ------------------------------------------------------------------
        // 9. Merge env vars into a working copy of the wrapped config
        // ------------------------------------------------------------------
        sub.subTask("Injecting mirrord environment…");
        ILaunchConfigurationWorkingCopy wc = wrapped.getWorkingCopy();

        Map<String, String> finalEnv = new HashMap<>(
                wc.getAttribute(MirrordPluginConstants.ATTR_ENVIRONMENT_VARIABLES,
                        Collections.<String, String>emptyMap()));
        finalEnv.putAll(execution.env);
        for (String key : execution.envToUnset) {
            finalEnv.remove(key);
        }

        wc.setAttribute(MirrordPluginConstants.ATTR_ENVIRONMENT_VARIABLES, finalEnv);
        // Ensure env vars are appended to the system env, not replacing it
        wc.setAttribute(MirrordPluginConstants.ATTR_APPEND_ENVIRONMENT_VARIABLES, true);
        sub.worked(5);

        // ------------------------------------------------------------------
        // 10. Resolve the wrapped delegate and launch
        // ------------------------------------------------------------------
        sub.subTask("Launching " + wrappedConfigName + "…");
        ILaunchDelegate[] delegates = wrapped.getType()
                .getDelegates(Collections.singleton(mode));

        if (delegates.length == 0) {
            throw error("No launch delegate found for '" + wrapped.getType().getName() +
                    "' in mode '" + mode + "'.");
        }

        ILaunchConfigurationDelegate delegate = delegates[0].getDelegate();

        // Run pre-launch checks if the delegate supports them
        if (delegate instanceof ILaunchConfigurationDelegate2 del2) {
            if (!del2.preLaunchCheck(wc, mode, sub.split(5))) {
                return; // Pre-launch check aborted the launch
            }
            if (!del2.finalLaunchCheck(wc, mode, sub.split(5))) {
                return;
            }
        }

        // Hand off to the wrapped delegate, passing our ILaunch so all processes/debug
        // targets appear under this single launch entry in the Debug view.
        delegate.launch(wc, mode, launch, sub.split(15));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Looks up a launch configuration by name across all known types. */
    private static ILaunchConfiguration findLaunchConfig(String name) throws CoreException {
        ILaunchManager lm = DebugPlugin.getDefault().getLaunchManager();
        for (ILaunchConfiguration cfg : lm.getLaunchConfigurations()) {
            // Skip our own type to avoid infinite recursion
            if (MirrordPluginConstants.LAUNCH_TYPE_ID.equals(cfg.getType().getIdentifier())) {
                continue;
            }
            if (name.equals(cfg.getName())) return cfg;
        }
        return null;
    }

    /**
     * Adds language-specific environment variables that help mirrord identify and
     * handle the debugger process (mirrors the VSCode extension's debugger.ts logic).
     */
    private static void addLanguageSpecificEnv(Map<String, String> env, String launchTypeId) {
        // Java Application / JUnit / Maven debug
        if (launchTypeId != null && (launchTypeId.contains("java") || launchTypeId.contains("Java"))) {
            env.putIfAbsent(MirrordPluginConstants.ENV_DETECT_DEBUGGER_PORT, "javaagent");
        }
        // Always ignore the Eclipse/JDWP debug port range
        env.putIfAbsent(MirrordPluginConstants.ENV_IGNORE_DEBUGGER_PORTS, "45000-65535");
    }

    private static CoreException error(String message) {
        return new CoreException(new Status(IStatus.ERROR,
                MirrordPluginConstants.PLUGIN_ID, message));
    }
}
