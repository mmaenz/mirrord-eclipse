package dev.mirrord.eclipse.ui;

import dev.mirrord.eclipse.Activator;
import dev.mirrord.eclipse.MirrordPluginConstants;
import dev.mirrord.eclipse.binary.BinaryManager;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * Window → Preferences → mirrord
 *
 * <p>Global preferences for the mirrord Eclipse integration (applies to all workspaces).
 * Per-launch overrides are set on the individual Debug Configuration.
 */
public class MirrordPreferencePage extends FieldEditorPreferencePage
        implements IWorkbenchPreferencePage {

    public MirrordPreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.prefs());
        setDescription(
                "Global settings for the mirrord Eclipse integration.\n" +
                "These can be overridden per launch configuration.");
    }

    @Override
    public void init(IWorkbench workbench) {}

    @Override
    protected void createFieldEditors() {
        addField(new FileFieldEditor(
                MirrordPluginConstants.PREF_BINARY_PATH,
                "mirrord binary path (leave empty to auto-detect):",
                true,
                getFieldEditorParent()));

        addField(new StringFieldEditor(
                MirrordPluginConstants.PREF_AUTO_UPDATE,
                "Auto-update binary (true / false / specific version like 3.68.0):",
                getFieldEditorParent()));

        // Info label
        Label info = new Label(getFieldEditorParent(), SWT.WRAP);
        info.setText(
                "When 'Auto-update' is true, mirrord will check https://version.mirrord.dev on " +
                "each launch and download the latest version if needed.\n" +
                "Set to 'false' to use whatever binary is on your PATH.\n" +
                "Set to a specific version string (e.g. '3.68.0') to pin to that release.");
        info.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    }
}
