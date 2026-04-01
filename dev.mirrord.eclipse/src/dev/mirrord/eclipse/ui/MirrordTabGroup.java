package dev.mirrord.eclipse.ui;

import org.eclipse.debug.ui.AbstractLaunchConfigurationTabGroup;
import org.eclipse.debug.ui.CommonTab;
import org.eclipse.debug.ui.EnvironmentTab;
import org.eclipse.debug.ui.ILaunchConfigurationDialog;
import org.eclipse.debug.ui.ILaunchConfigurationTab;

/**
 * Tab group for the mirrord launch configuration type.
 *
 * <p>Tabs shown in the Debug Configurations dialog:
 * <ol>
 *   <li><b>mirrord</b> ({@link MirrordMainTab}) – wrapped config + mirrord settings</li>
 *   <li><b>Environment</b> ({@link EnvironmentTab}) – extra environment variables</li>
 *   <li><b>Common</b> ({@link CommonTab}) – favourite launches, shared config, console I/O</li>
 * </ol>
 */
public class MirrordTabGroup extends AbstractLaunchConfigurationTabGroup {

    @Override
    public void createTabs(ILaunchConfigurationDialog dialog, String mode) {
        setTabs(new ILaunchConfigurationTab[]{
                new MirrordMainTab(),
                new EnvironmentTab(),
                new CommonTab()
        });
    }
}
