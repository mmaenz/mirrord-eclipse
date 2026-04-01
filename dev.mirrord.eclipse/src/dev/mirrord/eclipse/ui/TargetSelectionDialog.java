package dev.mirrord.eclipse.ui;

import dev.mirrord.eclipse.Activator;
import dev.mirrord.eclipse.MirrordPluginConstants;
import dev.mirrord.eclipse.api.MirrordAPI;
import dev.mirrord.eclipse.api.MirrordLsOutput;
import dev.mirrord.eclipse.api.MirrordTarget;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dialog that lets the user choose a Kubernetes target before each mirrord launch.
 *
 * <p>Mirrors the VSCode extension's {@code targetQuickPick.ts} behaviour:
 * <ul>
 *   <li>Lists available targets grouped by type (deployment / rollout / pod)</li>
 *   <li>Namespace selector that re-fetches targets when changed</li>
 *   <li>"Targetless" option for running without impersonating any pod</li>
 * </ul>
 */
public class TargetSelectionDialog extends TitleAreaDialog {

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final MirrordAPI api;
    private final String configPath;
    private MirrordLsOutput lsOutput;

    /** Flat list of display items (includes a "targetless" pseudo-entry). */
    private final List<TargetItem> items = new ArrayList<>();

    // Widgets
    private Combo nsCombo;
    private TableViewer targetViewer;
    private Button refreshButton;

    // Result
    private String selectedTarget;     // null → targetless
    private String selectedNamespace;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public TargetSelectionDialog(Shell parent, MirrordAPI api,
                                 String configPath, MirrordLsOutput initialOutput) {
        super(parent);
        this.api = api;
        this.configPath = configPath;
        this.lsOutput = initialOutput;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    // -----------------------------------------------------------------------
    // Dialog construction
    // -----------------------------------------------------------------------

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("mirrord – Select Kubernetes Target");
        shell.setMinimumSize(560, 400);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        setTitle("Select Kubernetes Target");
        setMessage("Choose the pod or deployment that mirrord should impersonate.\n" +
                "Select \"Targetless\" to run without attaching to any target.");

        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        container.setLayout(new GridLayout(3, false));

        // ---- Namespace row ----
        new Label(container, SWT.NONE).setText("Namespace:");

        nsCombo = new Combo(container, SWT.READ_ONLY | SWT.DROP_DOWN);
        nsCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        refreshButton = new Button(container, SWT.PUSH);
        refreshButton.setText("Refresh");
        refreshButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                refreshTargets(getSelectedNamespaceFromCombo());
            }
        });

        nsCombo.addSelectionListener(new SelectionListener() {
			
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				refreshTargets(getSelectedNamespaceFromCombo());
				
			}
			
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				refreshTargets(getSelectedNamespaceFromCombo());
				
			}
		});
        
        // ---- Target table ----
        Label lbl = new Label(container, SWT.NONE);
        lbl.setText("Target:");
        lbl.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));

        targetViewer = new TableViewer(container,
                SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL);
        targetViewer.getTable().setLayoutData(
                new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
        targetViewer.getTable().setHeaderVisible(true);
        targetViewer.getTable().setLinesVisible(true);
        targetViewer.setContentProvider(ArrayContentProvider.getInstance());

        TableViewerColumn typeCol = new TableViewerColumn(targetViewer, SWT.NONE);
        typeCol.getColumn().setText("Type");
        typeCol.getColumn().setWidth(110);
        typeCol.setLabelProvider(new ColumnLabelProvider() {
            @Override public String getText(Object e) {
                return e instanceof TargetItem t ? t.type : "";
            }
        });

        TableViewerColumn nameCol = new TableViewerColumn(targetViewer, SWT.NONE);
        nameCol.getColumn().setText("Name");
        nameCol.getColumn().setWidth(280);
        nameCol.setLabelProvider(new ColumnLabelProvider() {
            @Override public String getText(Object e) {
                return e instanceof TargetItem t ? t.label : "";
            }
        });

        TableViewerColumn availCol = new TableViewerColumn(targetViewer, SWT.NONE);
        availCol.getColumn().setText("Available");
        availCol.getColumn().setWidth(80);
        availCol.setLabelProvider(new ColumnLabelProvider() {
            @Override public String getText(Object e) {
                if (e instanceof TargetItem t && !t.isTargetless) {
                    return t.available ? "✓" : "—";
                }
                return "";
            }
        });

        targetViewer.addSelectionChangedListener(
                event -> validateAndEnableOk());

        // ---- Populate initial data ----
        populateNamespaces(lsOutput);
        populateTargets(lsOutput);

        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Launch", true);
        createButton(parent, IDialogConstants.CANCEL_ID,
                IDialogConstants.CANCEL_LABEL, false);
        validateAndEnableOk();
    }

    // -----------------------------------------------------------------------
    // UI logic
    // -----------------------------------------------------------------------

    private void populateNamespaces(MirrordLsOutput output) {
        nsCombo.removeAll();
        List<String> ns = output.namespaces != null ? output.namespaces : List.of();
        for (String n : ns) nsCombo.add(n);

        if (output.currentNamespace != null && !output.currentNamespace.isBlank()) {
            int idx = nsCombo.indexOf(output.currentNamespace);
            if (idx >= 0) nsCombo.select(idx);
            else { nsCombo.add(output.currentNamespace); nsCombo.select(nsCombo.getItemCount() - 1); }
        } else if (nsCombo.getItemCount() > 0) {
            nsCombo.select(0);
        }
    }

    private void populateTargets(MirrordLsOutput output) {
        items.clear();

        // "Targetless" pseudo-entry at the top
        items.add(TargetItem.TARGETLESS);

        for (MirrordTarget t : output.targets) {
            items.add(new TargetItem(t.path, t.getType(), t.getName(), t.available));
        }

        targetViewer.setInput(items.toArray());
        if (!items.isEmpty()) {
            targetViewer.getTable().select(0); // pre-select "Targetless"
        }
        validateAndEnableOk();
    }

    private void validateAndEnableOk() {
        Button ok = getButton(IDialogConstants.OK_ID);
        if (ok == null) return;
        ok.setEnabled(!targetViewer.getStructuredSelection().isEmpty());
    }

    private String getSelectedNamespaceFromCombo() {
        int idx = nsCombo.getSelectionIndex();
        return idx >= 0 ? nsCombo.getItem(idx) : null;
    }

    /** Re-fetches targets for the given namespace in a background Job. */
    private void refreshTargets(String namespace) {
        refreshButton.setEnabled(false);
        nsCombo.setEnabled(false);
        targetViewer.getTable().setEnabled(false);

        Job job = new Job("Fetching mirrord targets") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    MirrordLsOutput fresh = api.listTargets(
                            configPath,
                            new HashMap<>(),
                            MirrordPluginConstants.SUPPORTED_TARGET_TYPES,
                            namespace);
                    Display.getDefault().asyncExec(() -> {
                        if (getShell() == null || getShell().isDisposed()) return;
                        lsOutput = fresh;
                        populateNamespaces(fresh);
                        populateTargets(fresh);
                        refreshButton.setEnabled(true);
                        nsCombo.setEnabled(true);
                        targetViewer.getTable().setEnabled(true);
                    });
                } catch (Exception e) {
                    Display.getDefault().asyncExec(() -> {
                        if (getShell() == null || getShell().isDisposed()) return;
                        setErrorMessage("Failed to refresh targets: " + e.getMessage());
                        refreshButton.setEnabled(true);
                        nsCombo.setEnabled(true);
                        targetViewer.getTable().setEnabled(true);
                    });
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }

    // -----------------------------------------------------------------------
    // OK button handler
    // -----------------------------------------------------------------------

    @Override
    protected void okPressed() {
        IStructuredSelection sel = targetViewer.getStructuredSelection();
        if (sel.isEmpty()) return;

        TargetItem item = (TargetItem) sel.getFirstElement();
        selectedTarget    = item.isTargetless ? "targetless" : item.path;
        selectedNamespace = getSelectedNamespaceFromCombo();
        super.okPressed();
    }

    // -----------------------------------------------------------------------
    // Result accessors
    // -----------------------------------------------------------------------

    /** Returns the selected target path or {@code "targetless"}. */
    public String getSelectedTarget() {
        return selectedTarget;
    }

    /** Returns the selected Kubernetes namespace, or {@code null}. */
    public String getSelectedNamespace() {
        return selectedNamespace;
    }

    // -----------------------------------------------------------------------
    // Internal model
    // -----------------------------------------------------------------------

    private static class TargetItem {
        static final TargetItem TARGETLESS = new TargetItem(null, "", "Targetless", true);

        final String  path;
        final String  type;
        final String  label;
        final boolean available;
        final boolean isTargetless;

        TargetItem(String path, String type, String label, boolean available) {
            this.path        = path;
            this.type        = type;
            this.label       = label;
            this.available   = available;
            this.isTargetless = (path == null);
        }
    }
}
