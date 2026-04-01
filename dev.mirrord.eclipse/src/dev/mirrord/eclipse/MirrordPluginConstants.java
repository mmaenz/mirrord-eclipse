package dev.mirrord.eclipse;

/**
 * String constants for launch configuration attributes, preferences, and environment variables.
 */
public final class MirrordPluginConstants {

    // Plugin ID – must match Bundle-SymbolicName
    public static final String PLUGIN_ID = "dev.mirrord.eclipse";

    // Launch configuration type ID – must match plugin.xml
    public static final String LAUNCH_TYPE_ID = "dev.mirrord.eclipse.launchConfigurationType";

    // -----------------------------------------------------------------------
    // Launch configuration attribute keys (stored in .launch files)
    // -----------------------------------------------------------------------

    /** Name of the existing launch configuration to wrap. */
    public static final String ATTR_WRAPPED_LAUNCH = PLUGIN_ID + ".wrappedLaunch";

    /** Absolute path to the mirrord config file (.mirrord/mirrord.json etc.). */
    public static final String ATTR_MIRRORD_CONFIG_PATH = PLUGIN_ID + ".configPath";

    /**
     * Override path to the mirrord binary. Empty string means auto-detect.
     * Corresponds to the {@code mirrord.binaryPath} VSCode setting.
     */
    public static final String ATTR_BINARY_PATH = PLUGIN_ID + ".binaryPath";

    /**
     * Whether to automatically download/update the mirrord binary.
     * Defaults to {@code true}.
     */
    public static final String ATTR_AUTO_UPDATE = PLUGIN_ID + ".autoUpdate";

    // -----------------------------------------------------------------------
    // Eclipse standard environment variable attribute key
    // (used by org.eclipse.debug.ui.EnvironmentTab and all launch delegates)
    // -----------------------------------------------------------------------

    /**
     * Standard Eclipse attribute key for environment variables in a launch config.
     * Stores a {@code Map<String,String>} where keys are variable names and values are values.
     */
    public static final String ATTR_ENVIRONMENT_VARIABLES =
            "org.eclipse.debug.core.environmentVariables";

    /**
     * Standard Eclipse attribute key controlling whether mirrord env vars are appended to
     * the system environment (true = append, false = replace entirely).
     */
    public static final String ATTR_APPEND_ENVIRONMENT_VARIABLES =
            "org.eclipse.debug.core.appendEnvironmentVariables";

    // -----------------------------------------------------------------------
    // Preference keys (IPreferenceStore)
    // -----------------------------------------------------------------------

    public static final String PREF_BINARY_PATH    = "binaryPath";
    public static final String PREF_AUTO_UPDATE    = "autoUpdate";

    // -----------------------------------------------------------------------
    // mirrord environment variables set by the IDE integration
    // -----------------------------------------------------------------------

    public static final String ENV_PROGRESS_MODE         = "MIRRORD_PROGRESS_MODE";
    public static final String ENV_PROGRESS_SUPPORT_IDE  = "MIRRORD_PROGRESS_SUPPORT_IDE";
    public static final String ENV_IDE_NAME              = "MIRRORD_IDE_NAME";
    public static final String ENV_LS_RICH_OUTPUT        = "MIRRORD_LS_RICH_OUTPUT";
    public static final String ENV_LS_TARGET_TYPES       = "MIRRORD_LS_TARGET_TYPES";
    public static final String ENV_TARGET_NAMESPACE      = "MIRRORD_TARGET_NAMESPACE";
    public static final String ENV_BRANCH_NAME           = "MIRRORD_BRANCH_NAME";
    public static final String ENV_DETECT_DEBUGGER_PORT  = "MIRRORD_DETECT_DEBUGGER_PORT";
    public static final String ENV_IGNORE_DEBUGGER_PORTS = "MIRRORD_IGNORE_DEBUGGER_PORTS";
    public static final String ENV_SKIP_PROCESSES        = "MIRRORD_SKIP_PROCESSES";

    // -----------------------------------------------------------------------
    // mirrord version endpoint
    // -----------------------------------------------------------------------

    public static final String VERSION_ENDPOINT =
            "https://version.mirrord.dev/v1/version";

    public static final String GITHUB_RELEASE_BASE =
            "https://github.com/metalbear-co/mirrord/releases/download";

    // -----------------------------------------------------------------------
    // Supported target types for mirrord ls
    // -----------------------------------------------------------------------

    public static final String[] SUPPORTED_TARGET_TYPES =
            {"deployment", "rollout", "pod"};

    private MirrordPluginConstants() {}
}
