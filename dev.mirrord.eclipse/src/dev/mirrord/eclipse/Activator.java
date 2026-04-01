package dev.mirrord.eclipse;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * Plugin activator. Provides access to the plugin's preference store and logger.
 */
public class Activator extends AbstractUIPlugin {

    private static Activator instance;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        initPreferenceDefaults();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        instance = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return instance;
    }

    /** Returns the Eclipse logger for this plugin. */
    public static ILog logger() {
        return Platform.getLog(Activator.class);
    }

    /** Returns the plugin's preference store. */
    public static IPreferenceStore prefs() {
        return instance.getPreferenceStore();
    }

    /**
     * Returns the absolute path to the plugin's state/storage directory.
     * Used to store the downloaded mirrord binary between sessions.
     */
    public static String stateLocation() {
        return instance.getStateLocation().toOSString();
    }

    private void initPreferenceDefaults() {
        IPreferenceStore store = getPreferenceStore();
        store.setDefault(MirrordPluginConstants.PREF_BINARY_PATH, "");
        store.setDefault(MirrordPluginConstants.PREF_AUTO_UPDATE, true);
    }
}
