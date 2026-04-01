package dev.mirrord.eclipse.ui;

import dev.mirrord.eclipse.Activator;
import dev.mirrord.eclipse.MirrordPluginConstants;
import dev.mirrord.eclipse.config.MirrordConfigManager;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.ui.AbstractLaunchConfigurationTab;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The main (and only) tab shown in the Debug Configurations dialog for the mirrord launch type.
 *
 * <h3>Sections</h3>
 * <ol>
 *   <li><b>Wrapped Launch Configuration</b> – which existing config to run through mirrord</li>
 *   <li><b>mirrord Configuration File</b> – optional {@code .mirrord/mirrord.json} path</li>
 *   <li><b>Binary</b> – optional custom binary path + auto-update toggle</li>
 * </ol>
 */
public class MirrordMainTab extends AbstractLaunchConfigurationTab {

    // Widgets
    private Combo      wrappedConfigCombo;
    private Button     browseConfigFileButton;
    private Text       configFileText;
    private Button     createDefaultConfigButton;
    private Text       binaryPathText;
    private Button     binaryBrowseButton;
    private Button     autoUpdateCheckbox;

    // -----------------------------------------------------------------------
    // Tab metadata
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "mirrord";
    }

    @Override
    public String getId() {
        return "dev.mirrord.eclipse.mainTab";
    }

    // -----------------------------------------------------------------------
    // UI construction
    // -----------------------------------------------------------------------

    @Override
    public void createControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        setControl(root);

        createWrappedConfigGroup(root);
        createMirrordConfigGroup(root);
        createBinaryGroup(root);
    }

    private void createWrappedConfigGroup(Composite parent) {
        Group group = createGroup(parent, "Wrapped Launch Configuration");

        new Label(group, SWT.NONE).setText(
                "Select the existing launch configuration that mirrord should wrap:");

        wrappedConfigCombo = new Combo(group, SWT.READ_ONLY | SWT.DROP_DOWN);
        wrappedConfigCombo.setLayoutData(
                new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        wrappedConfigCombo.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { updateLaunchConfigurationDialog(); }
        });

        Label hint = new Label(group, SWT.WRAP);
        hint.setText("mirrord will inject Kubernetes environment variables and network interception " +
                "into this launch configuration before running it.");
        hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        populateLaunchConfigs();
    }

    private void createMirrordConfigGroup(Composite parent) {
        Group group = createGroup(parent, "mirrord Configuration File (optional)");

        new Label(group, SWT.NONE).setText("Config file path:");

        Composite row = new Composite(group, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        row.setLayout(new GridLayout(2, false));

        configFileText = new Text(row, SWT.BORDER | SWT.SINGLE);
        configFileText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        configFileText.setMessage("Leave empty to use .mirrord/mirrord.json (auto-detected)");
        configFileText.addModifyListener(e -> updateLaunchConfigurationDialog());

        browseConfigFileButton = new Button(row, SWT.PUSH);
        browseConfigFileButton.setText("Browse…");
        browseConfigFileButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { browseForConfigFile(); }
        });

        createDefaultConfigButton = new Button(group, SWT.PUSH);
        createDefaultConfigButton.setText("Create default .mirrord/mirrord.json in first project");
        createDefaultConfigButton.setLayoutData(
                new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        createDefaultConfigButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { createDefaultConfig(); }
        });
    }

    private void createBinaryGroup(Composite parent) {
        Group group = createGroup(parent, "mirrord Binary");

        autoUpdateCheckbox = new Button(group, SWT.CHECK);
        autoUpdateCheckbox.setText("Auto-download and update the mirrord binary");
        autoUpdateCheckbox.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        autoUpdateCheckbox.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                binaryPathText.setEnabled(!autoUpdateCheckbox.getSelection());
                binaryBrowseButton.setEnabled(!autoUpdateCheckbox.getSelection());
                updateLaunchConfigurationDialog();
            }
        });

        new Label(group, SWT.NONE).setText("Custom binary path:");

        Composite row = new Composite(group, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        row.setLayout(new GridLayout(2, false));

        binaryPathText = new Text(row, SWT.BORDER | SWT.SINGLE);
        binaryPathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        binaryPathText.setMessage("Leave empty to auto-detect or download");
        binaryPathText.addModifyListener(e -> updateLaunchConfigurationDialog());

        binaryBrowseButton = new Button(row, SWT.PUSH);
        binaryBrowseButton.setText("Browse…");
        binaryBrowseButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { browseForBinary(); }
        });
    }

    // -----------------------------------------------------------------------
    // ILaunchConfigurationTab – read / write
    // -----------------------------------------------------------------------

    @Override
    public void setDefaults(ILaunchConfigurationWorkingCopy config) {
        config.setAttribute(MirrordPluginConstants.ATTR_WRAPPED_LAUNCH, "");
        config.setAttribute(MirrordPluginConstants.ATTR_MIRRORD_CONFIG_PATH, "");
        config.setAttribute(MirrordPluginConstants.ATTR_BINARY_PATH, "");
        config.setAttribute(MirrordPluginConstants.ATTR_AUTO_UPDATE, true);
    }

    @Override
    public void initializeFrom(ILaunchConfiguration config) {
        try {
            // Wrapped config
            String wrapped = config.getAttribute(MirrordPluginConstants.ATTR_WRAPPED_LAUNCH, "");
            selectComboItem(wrappedConfigCombo, wrapped);

            // Config file
            String cfgPath = config.getAttribute(MirrordPluginConstants.ATTR_MIRRORD_CONFIG_PATH, "");
            configFileText.setText(cfgPath);

            // Binary
            String binPath = config.getAttribute(MirrordPluginConstants.ATTR_BINARY_PATH, "");
            binaryPathText.setText(binPath);

            boolean autoUpdate = config.getAttribute(MirrordPluginConstants.ATTR_AUTO_UPDATE, true);
            autoUpdateCheckbox.setSelection(autoUpdate);
            binaryPathText.setEnabled(!autoUpdate);
            binaryBrowseButton.setEnabled(!autoUpdate);

        } catch (Exception e) {
            Activator.logger().error("Error reading launch config: " + e.getMessage());
        }
    }

    @Override
    public void performApply(ILaunchConfigurationWorkingCopy config) {
        int selIdx = wrappedConfigCombo.getSelectionIndex();
        String wrapped = selIdx >= 0 ? wrappedConfigCombo.getItem(selIdx) : "";
        config.setAttribute(MirrordPluginConstants.ATTR_WRAPPED_LAUNCH, wrapped);
        config.setAttribute(MirrordPluginConstants.ATTR_MIRRORD_CONFIG_PATH,
                configFileText.getText().trim());
        config.setAttribute(MirrordPluginConstants.ATTR_BINARY_PATH,
                binaryPathText.getText().trim());
        config.setAttribute(MirrordPluginConstants.ATTR_AUTO_UPDATE,
                autoUpdateCheckbox.getSelection());
    }

    @Override
    public boolean isValid(ILaunchConfiguration config) {
        setErrorMessage(null);
        setMessage(null);

        // Wrapped config must be selected
        int selIdx = wrappedConfigCombo.getSelectionIndex();
        if (selIdx < 0 || wrappedConfigCombo.getItem(selIdx).isBlank()) {
            setErrorMessage("Select a launch configuration to wrap.");
            return false;
        }

        // Config file: if specified, must exist
        String cfgPath = configFileText.getText().trim();
        if (!cfgPath.isBlank() && !new File(cfgPath).exists()) {
            setErrorMessage("mirrord config file not found: " + cfgPath);
            return false;
        }

        // Binary: if specified, must exist
        String binPath = binaryPathText.getText().trim();
        if (!binPath.isBlank() && !new File(binPath).exists()) {
            setErrorMessage("mirrord binary not found: " + binPath);
            return false;
        }

        return true;
    }

    // -----------------------------------------------------------------------
    // Button actions
    // -----------------------------------------------------------------------

    private void browseForConfigFile() {
        FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
        dialog.setText("Select mirrord configuration file");
        dialog.setFilterNames(new String[]{"mirrord config files", "All files"});
        dialog.setFilterExtensions(new String[]{"*.json;*.toml;*.yml;*.yaml", "*"});
        String result = dialog.open();
        if (result != null) {
            configFileText.setText(result);
        }
    }

    private void browseForBinary() {
        FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
        dialog.setText("Select mirrord binary");
        dialog.setFilterNames(new String[]{"All files"});
        dialog.setFilterExtensions(new String[]{"*"});
        String result = dialog.open();
        if (result != null) {
            binaryPathText.setText(result);
        }
    }

    private void createDefaultConfig() {
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        IProject open = Arrays.stream(projects).filter(IProject::isOpen).findFirst().orElse(null);
        if (open == null) {
            MessageDialog.openInformation(getShell(), "mirrord",
                    "No open project found in the workspace.");
            return;
        }
        String path = MirrordConfigManager.createDefaultConfig(open);
        if (path != null) {
            configFileText.setText(path);
            MessageDialog.openInformation(getShell(), "mirrord",
                    "Created default config: " + path);
        } else {
            MessageDialog.openError(getShell(), "mirrord",
                    "Could not create default config in project: " + open.getName());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void populateLaunchConfigs() {
        try {
            List<String> names = new ArrayList<>();
            names.add(""); // blank "please select" entry
            for (ILaunchConfiguration cfg :
                    DebugPlugin.getDefault().getLaunchManager().getLaunchConfigurations()) {
                // Exclude our own type to prevent nesting
                if (!MirrordPluginConstants.LAUNCH_TYPE_ID.equals(
                        cfg.getType().getIdentifier())) {
                    names.add(cfg.getName());
                }
            }
            wrappedConfigCombo.setItems(names.toArray(new String[0]));
        } catch (Exception e) {
            Activator.logger().error("Error listing launch configs: " + e.getMessage());
        }
    }

    private void selectComboItem(Combo combo, String value) {
        String[] items = combo.getItems();
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(value)) {
                combo.select(i);
                return;
            }
        }
        combo.select(0);
    }

    private Group createGroup(Composite parent, String title) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(title);
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        group.setLayout(new GridLayout(2, false));
        return group;
    }
}
