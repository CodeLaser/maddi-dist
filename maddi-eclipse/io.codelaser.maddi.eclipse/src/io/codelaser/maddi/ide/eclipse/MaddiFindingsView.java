/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.codelaser.maddi.ide.eclipse;

import io.codelaser.maddi.ide.client.AnalysisModel;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "maddi Findings" view: what the run is doing, what it produced, and which daemon produced it — the Eclipse
 * counterpart of the IntelliJ tool window ({@code MaddiFindingsPanel}), which explains why each part is there.
 * <ul>
 *   <li><b>Header</b> — the state (with an elapsed clock while running, because the daemon reports its own time
 *       only at the end), the phase and last message, and the daemon: where it was launched from and its build
 *       stamp. A stale bundled daemon has been diagnosed as an analyzer regression before.</li>
 *   <li><b>Findings tree</b> — grouped by file, each finding with its why-chain as children, and the analysis's
 *       initialization problems (sources not analysed, and why). Marked stale while a new run is under way.
 *       Double-click goes to the location.</li>
 *   <li><b>Run log</b> — every status frame, heartbeat, pass and error, timestamped.</li>
 * </ul>
 * The toolbar runs the last project's analysis again, stops a running one, restarts the daemon, and clears the log.
 */
public class MaddiFindingsView extends ViewPart {

    public static final String ID = "io.codelaser.maddi.eclipse.findingsView";

    /** A row of the tree: a file, a finding (with its causes), or an initialization problem. */
    public record Row(String label, String severity, String uri, Integer line, Integer col, List<Row> children) {
    }

    private Label stateLabel;
    private Label phaseLabel;
    private Label daemonLabel;
    private TreeViewer viewer;
    private Text log;
    private Action analyze;
    private Action restart;
    private Action stop;
    private final Runnable resultListener = () -> onUiThread(this::renderFindings);
    private final Runnable runListener = () -> onUiThread(this::renderRun);
    private final Runnable clock = this::tick;

    @Override
    public void createPartControl(Composite parent) {
        Composite content = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        content.setLayout(layout);

        Composite header = new Composite(content, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout headerLayout = new GridLayout(1, false);
        headerLayout.marginWidth = 8;
        headerLayout.marginHeight = 4;
        headerLayout.verticalSpacing = 2;
        header.setLayout(headerLayout);
        stateLabel = headerLabel(header);
        phaseLabel = headerLabel(header);
        daemonLabel = headerLabel(header);

        SashForm sash = new SashForm(content, SWT.VERTICAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        viewer = new TreeViewer(sash, SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        viewer.setContentProvider(new RowContentProvider());
        viewer.setLabelProvider(new RowLabelProvider());
        viewer.addDoubleClickListener(e -> openSelected());
        log = new Text(sash, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        sash.setWeights(60, 40);

        buildToolbar(getViewSite().getActionBars().getToolBarManager());

        MaddiResults.get().addListener(resultListener);
        MaddiRun.get().addListener(runListener);
        renderFindings();
        renderRun();
    }

    private static Label headerLabel(Composite header) {
        Label label = new Label(header, SWT.NONE);
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return label;
    }

    private void buildToolbar(IToolBarManager toolbar) {
        analyze = new Action("Analyze") {
            @Override
            public void run() {
                PlatformUI.getWorkbench().saveAllEditors(false); // the daemon reads the files on disk
                if (!MaddiAnalysis.rerun()) MaddiAnalysis.reportNoProject();
            }
        };
        analyze.setToolTipText("Analyze the last analysed project again (Project → Analyze with maddi for another)");
        stop = new Action("Stop analysis") {
            @Override
            public void run() {
                MaddiAnalysis.stop();
            }
        };
        stop.setToolTipText("Stop the running analysis; the next one starts a fresh daemon");
        stop.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_ELCL_STOP));
        restart = new Action("Restart daemon") {
            @Override
            public void run() {
                MaddiAnalysis.restartDaemon();
            }
        };
        restart.setToolTipText("Stop the daemon; the next analysis starts a fresh one");
        Action clear = new Action("Clear log") {
            @Override
            public void run() {
                MaddiRun.get().clearLog();
            }
        };
        clear.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_ETOOL_CLEAR));
        toolbar.add(analyze);
        toolbar.add(stop);
        toolbar.add(restart);
        toolbar.add(clear);
    }

    @Override
    public void setFocus() {
        if (viewer != null) viewer.getControl().setFocus();
    }

    @Override
    public void dispose() {
        MaddiResults.get().removeListener(resultListener);
        MaddiRun.get().removeListener(runListener);
        super.dispose();
    }

    private void onUiThread(Runnable r) {
        Display display = Display.getDefault();
        if (display.isDisposed()) return;
        display.asyncExec(() -> {
            if (viewer != null && !viewer.getControl().isDisposed()) r.run();
        });
    }

    // ---- rendering -------------------------------------------------------------------------------------------

    private void renderFindings() {
        viewer.setInput(rows(MaddiResults.get().latest()));
        viewer.expandAll();
    }

    /** The tree's top-level rows for a result: one per file with findings, then the initialization problems. */
    public static List<Row> rows(AnalysisModel.Result result) {
        List<Row> rows = new ArrayList<>();
        if (result == null) return rows;
        Map<String, List<Row>> byFile = new LinkedHashMap<>();
        for (AnalysisModel.Finding f : result.findings()) {
            byFile.computeIfAbsent(fileName(f.uri()), k -> new ArrayList<>()).add(findingRow(f));
        }
        byFile.forEach((file, findings) -> rows.add(new Row(file, null, null, null, null, findings)));
        if (result.initializationProblems() != null) {
            for (String problem : result.initializationProblems()) {
                rows.add(new Row("[initialization] " + problem, "WARN", null, null, null, List.of()));
            }
        }
        return rows;
    }

    private static Row findingRow(AnalysisModel.Finding f) {
        List<Row> causes = new ArrayList<>();
        if (f.causes() != null) for (AnalysisModel.Finding cause : f.causes()) causes.add(findingRow(cause));
        String severity = AnalysisModel.isNearMiss(f) ? "INFO" : f.severity();
        return new Row("[" + f.severity() + "/" + f.category() + "] " + f.message(), severity, f.uri(),
                f.beginLine(), f.beginCol(), causes);
    }

    private void renderRun() {
        MaddiRun.State state = MaddiRun.get().state();
        if (state.running()) {
            long seconds = (System.nanoTime() - state.startedNanos()) / 1_000_000_000L;
            stateLabel.setText("running — " + seconds + "s"
                               + (state.stale() ? " (the findings below are the PREVIOUS run's)" : ""));
        } else {
            stateLabel.setText(state.terminal().isEmpty() ? "not run yet" : state.terminal());
        }
        stateLabel.setForeground(state.failed() ? Display.getDefault().getSystemColor(SWT.COLOR_RED) : null);
        phaseLabel.setText(state.phase().isEmpty() ? ""
                : state.phase() + (state.message().isEmpty() ? "" : ": " + state.message()));
        daemonLabel.setText(state.daemonInstall().isEmpty()
                ? "daemon: not started yet. It starts with the first analysis (Project → Analyze with maddi); its"
                  + " output goes to " + MaddiAnalysis.daemonLogFile()
                : "daemon: build " + state.daemonBuild() + " at " + state.daemonInstall());
        String text = MaddiRun.get().logText();
        if (!text.equals(log.getText())) {
            log.setText(text);
            log.setTopIndex(log.getLineCount() - 1);
        }
        analyze.setEnabled(!state.running());
        restart.setEnabled(!state.running());
        stop.setEnabled(state.running());
        stateLabel.getParent().layout();
        if (state.running()) Display.getDefault().timerExec(1000, clock);
    }

    /** The elapsed clock: re-render once a second while a run is in flight. */
    private void tick() {
        if (viewer == null || viewer.getControl().isDisposed()) return;
        renderRun();
    }

    // ---- navigation ------------------------------------------------------------------------------------------

    private void openSelected() {
        IStructuredSelection selection = viewer.getStructuredSelection();
        if (!(selection.getFirstElement() instanceof Row row) || row.uri() == null) return;
        IFile file = locate(row.uri());
        if (file == null) return;
        try {
            IEditorPart editor = IDE.openEditor(getSite().getPage(), file);
            if (editor instanceof ITextEditor text && row.line() != null) {
                IDocument document = text.getDocumentProvider().getDocument(text.getEditorInput());
                int offset = offset(document, row.line(), row.col());
                if (offset >= 0) text.selectAndReveal(offset, 0);
            }
        } catch (Exception e) {
            MaddiEclipsePlugin.error("could not open finding location", e);
        }
    }

    private static int offset(IDocument document, int line, Integer col) {
        if (document == null || line < 1 || line > document.getNumberOfLines()) return -1;
        try {
            int start = document.getLineOffset(line - 1);
            int length = document.getLineLength(line - 1);
            return start + Math.min(col == null ? 0 : Math.max(0, col - 1), length);
        } catch (BadLocationException e) {
            return -1;
        }
    }

    private static IFile locate(String uri) {
        try {
            IFile[] files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(URI.create(uri));
            return files.length == 0 ? null : files[0];
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String fileName(String uri) {
        if (uri == null) return "(no file)";
        int slash = uri.lastIndexOf('/');
        return slash < 0 ? uri : uri.substring(slash + 1);
    }

    private static final class RowContentProvider implements ITreeContentProvider {
        @Override
        public Object[] getElements(Object input) {
            return input instanceof List<?> list ? list.toArray() : new Object[0];
        }

        @Override
        public Object[] getChildren(Object element) {
            return element instanceof Row row ? row.children().toArray() : new Object[0];
        }

        @Override
        public Object getParent(Object element) {
            return null;
        }

        @Override
        public boolean hasChildren(Object element) {
            return element instanceof Row row && !row.children().isEmpty();
        }
    }

    private static final class RowLabelProvider extends LabelProvider {
        @Override
        public String getText(Object element) {
            return element instanceof Row row ? row.label() : "";
        }

        @Override
        public Image getImage(Object element) {
            if (!(element instanceof Row row) || row.severity() == null) return null;
            String key = switch (row.severity()) {
                case "ERROR" -> ISharedImages.IMG_OBJS_ERROR_TSK;
                case "INFO" -> ISharedImages.IMG_OBJS_INFO_TSK;
                default -> ISharedImages.IMG_OBJS_WARN_TSK;
            };
            return PlatformUI.getWorkbench().getSharedImages().getImage(key);
        }
    }
}
