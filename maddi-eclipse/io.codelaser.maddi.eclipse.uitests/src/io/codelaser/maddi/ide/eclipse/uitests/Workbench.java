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

package io.codelaser.maddi.ide.eclipse.uitests;

import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.ide.eclipse.HintFilter;
import io.codelaser.maddi.ide.eclipse.MaddiAnalysis;
import io.codelaser.maddi.ide.eclipse.MaddiCodeMiningProvider;
import io.codelaser.maddi.ide.eclipse.MaddiEclipsePlugin;
import io.codelaser.maddi.ide.eclipse.MaddiPreferences;
import io.codelaser.maddi.ide.eclipse.MaddiResults;
import io.codelaser.maddi.ide.eclipse.MaddiRun;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.codemining.ICodeMining;
import org.eclipse.jface.text.codemining.LineHeaderCodeMining;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.eclipse.ui.texteditor.ITextEditor;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What the UI tests do in the workbench: import the fixture project, set the preferences, open editors, run
 * "Analyze with maddi" through its command, and read back what the Java editor shows. The tests run on their own
 * thread (useUIThread=false); everything that touches the workbench goes through {@link #ui}.
 */
final class Workbench {

    static final String PROJECT = "Demo";
    static final String ANALYZE_COMMAND = "io.codelaser.maddi.eclipse.analyze";
    private static final long RUN_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private Workbench() {
    }

    /**
     * A hint as the editor draws it: its 0-based line, its column on that line for an inline (parameter) hint or -1
     * for one on its own line above the declaration, and its text.
     */
    record Hint(int line, int column, String label) {
        @Override
        public String toString() {
            return line + ":" + column + " " + label;
        }
    }

    /**
     * How a run ended: the run's state as the findings view shows it, the result's outcome (INCREMENTAL, CERTIFIED,
     * ...), and the daemon's progress messages of this run, in order and without repeats ("parse: re-parsing 1
     * changed type(s)").
     */
    record Run(MaddiRun.State state, String outcome, List<String> steps) {
    }

    // ------------------------------------------------------------------------------------------------ the project

    /** The fixture project, imported and built on first use: src/ on a JRE container, compliance 17. */
    static synchronized IJavaProject demoProject() throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT);
        if (project.exists()) return JavaCore.create(project);
        project.create(null);
        project.open(null);
        IProjectDescription description = project.getDescription();
        description.setNatureIds(new String[]{JavaCore.NATURE_ID});
        project.setDescription(description, null);

        IJavaProject javaProject = JavaCore.create(project);
        IFolder src = project.getFolder("src");
        src.create(true, true, null);
        IFolder bin = project.getFolder("bin");
        bin.create(true, true, null);
        javaProject.setRawClasspath(new IClasspathEntry[]{
                JavaCore.newSourceEntry(src.getFullPath()),
                JavaRuntime.getDefaultJREContainerEntry()}, bin.getFullPath(), null);
        Map<String, String> options = javaProject.getOptions(false);
        JavaCore.setComplianceOptions("17", options);
        javaProject.setOptions(options);

        IFolder demo = src.getFolder("demo");
        demo.create(true, true, null);
        Path fixture = Path.of(System.getProperty("maddi.uitest.fixture"), "demo");
        try (Stream<Path> files = Files.list(fixture)) {
            for (Path source : files.sorted().toList()) {
                try (InputStream in = Files.newInputStream(source)) {
                    demo.getFile(source.getFileName().toString()).create(in, true, null);
                }
            }
        }
        project.build(IncrementalProjectBuilder.FULL_BUILD, null);
        Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, null);
        return javaProject;
    }

    static IFile file(String name) {
        return ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT).getFile("src/demo/" + name);
    }

    // -------------------------------------------------------------------------------------------- the preferences

    /**
     * maddi's preferences for a test run: this machine's JDK and the daemon's installDist, every computed annotation
     * as a hint (so that the assertions do not depend on what the default filter hides), near misses on. Also JDT's
     * own switch for code minings, without which the Java editor draws none, maddi's included.
     */
    static void configure(boolean incremental) {
        String jdkHome = jdkHome();
        String daemonInstall = daemonInstall();
        ui(() -> {
            IPreferenceStore store = MaddiEclipsePlugin.get().getPreferenceStore();
            store.setValue(MaddiPreferences.JDK_HOME, jdkHome);
            store.setValue(MaddiPreferences.DAEMON_INSTALL, daemonInstall);
            store.setValue(MaddiPreferences.DAEMON_XMX_MB, 2048);
            store.setValue(MaddiPreferences.HINT_FILTER, HintFilter.ALL.name());
            store.setValue(MaddiPreferences.WARN_NEAR_MISSES, true);
            store.setValue(MaddiPreferences.INCREMENTAL_REANALYSIS, incremental);
            PreferenceConstants.getPreferenceStore().setValue(PreferenceConstants.EDITOR_CODEMINING_ENABLED, true);
            return null;
        });
    }

    /** MADDI_JDK_HOME, else the newest JDK 25+ that macOS knows of, else JAVA_HOME. */
    static String jdkHome() {
        String env = System.getenv("MADDI_JDK_HOME");
        if (env != null && !env.isBlank()) return env;
        Path javaHomeTool = Path.of("/usr/libexec/java_home");
        if (Files.isExecutable(javaHomeTool)) {
            try {
                Process process = new ProcessBuilder(javaHomeTool.toString(), "-v", "25+").start();
                String out = new String(process.getInputStream().readAllBytes()).strip();
                if (process.waitFor() == 0 && !out.isEmpty()) return out;
            } catch (Exception e) {
                // fall through to JAVA_HOME
            }
        }
        String javaHome = System.getenv("JAVA_HOME");
        assertNotNull(javaHome, "set MADDI_JDK_HOME to a JDK 25 or later");
        return javaHome;
    }

    /**
     * MADDI_DAEMON_INSTALL, else the Gradle installDist next door: the tests run the daemon as built, not the copy
     * bundled into the plugin, which is only as fresh as the last plugin build.
     */
    static String daemonInstall() {
        String env = System.getenv("MADDI_DAEMON_INSTALL");
        Path install = Path.of(env != null && !env.isBlank() ? env : System.getProperty("maddi.uitest.daemonInstall"));
        assertTrue(Files.isRegularFile(install.resolve("bin/maddi-ide-daemon")), () -> "no daemon at " + install
                + "; run ./gradlew :maddi-ide-daemon:installDist in maddi-dist, or set MADDI_DAEMON_INSTALL");
        try {
            return install.toRealPath().toString();
        } catch (Exception e) {
            return install.toAbsolutePath().normalize().toString();
        }
    }

    // ---------------------------------------------------------------------------------------------------- the run

    /**
     * Activate {@code editor} and run "Analyze with maddi" as the menu does: the handler takes the active editor's
     * project, saves the editors, and schedules the analysis job. Waits for the job, and fails on a failed run.
     */
    static Run analyze(ITextEditor editor) {
        ui(() -> {
            editor.getSite().getPage().activate(editor);
            PlatformUI.getWorkbench().getService(IHandlerService.class).executeCommand(ANALYZE_COMMAND, null);
            return null;
        });
        waitForAnalysis();
        ui(() -> null); // what the run posted to the UI thread (the refresh of the minings) has run
        MaddiRun.State state = MaddiRun.get().state();
        assertFalse(state.running(), "still running");
        assertFalse(state.failed(), () -> state.terminal() + "\n" + MaddiRun.get().logText());
        AnalysisModel.Result result = MaddiResults.get().latest();
        assertNotNull(result, "no result");
        return new Run(state, String.valueOf(result.outcome()), stepsOfLastRun());
    }

    private static void waitForAnalysis() {
        long deadline = System.currentTimeMillis() + RUN_TIMEOUT_MILLIS;
        while (analysisJobs() || MaddiAnalysis.isRunning()) {
            if (System.currentTimeMillis() > deadline) fail("the analysis did not end in time\n" + MaddiRun.get().logText());
            sleep(250);
        }
    }

    private static boolean analysisJobs() {
        return Arrays.stream(Job.getJobManager().find(null))
                .anyMatch(job -> job.getName().startsWith("maddi: analyzing"));
    }

    /** The run log after the last "--- Demo started" line, without the clock, each line once. */
    private static List<String> stepsOfLastRun() {
        List<String> lines = MaddiRun.get().logText().lines().toList();
        int start = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("--- " + PROJECT + " started")) start = i + 1;
        }
        LinkedHashSet<String> steps = new LinkedHashSet<>();
        for (String line : lines.subList(start, lines.size())) {
            // "HH:mm:ss  message"
            steps.add(line.length() > 10 ? line.substring(10) : line);
        }
        return List.copyOf(steps);
    }

    // ------------------------------------------------------------------------------------------------- the editor

    static ITextEditor open(String file) {
        return ui(() -> {
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            IEditorPart editor = IDE.openEditor(page, file(file), true);
            assertTrue(editor instanceof ITextEditor, () -> "not a text editor: " + editor);
            return (ITextEditor) editor;
        });
    }

    static ITextViewer viewer(ITextEditor editor) throws Exception {
        ITextViewer viewer = editor.getAdapter(ITextViewer.class);
        if (viewer != null) return viewer;
        Method getSourceViewer = AbstractTextEditor.class.getDeclaredMethod("getSourceViewer");
        getSourceViewer.setAccessible(true);
        return (ITextViewer) getSourceViewer.invoke(editor);
    }

    static IDocument document(ITextEditor editor) {
        return ui(() -> editor.getDocumentProvider().getDocument(editor.getEditorInput()));
    }

    /**
     * The hints maddi's code-mining provider gives this editor now: the provider the Java editor installs, asked as
     * the editor asks it, with the editor's own viewer and document. Sorted by place.
     */
    static List<Hint> hints(ITextEditor editor) {
        return ui(() -> {
            MaddiCodeMiningProvider provider = new MaddiCodeMiningProvider();
            provider.setContext(editor);
            ITextViewer viewer = viewer(editor);
            IDocument document = viewer.getDocument();
            List<Hint> hints = new ArrayList<>();
            try {
                for (ICodeMining mining : provider.provideCodeMinings(viewer, new NullProgressMonitor()).get()) {
                    Position position = mining.getPosition();
                    int line = document.getLineOfOffset(position.getOffset());
                    int column = mining instanceof LineHeaderCodeMining ? -1
                            : position.getOffset() - document.getLineOffset(line);
                    hints.add(new Hint(line, column, mining.getLabel().strip()));
                }
            } finally {
                provider.dispose();
            }
            hints.sort((a, b) -> a.toString().compareTo(b.toString()));
            return hints;
        });
    }

    /** The hints of each of {@code files}, each file opened in an editor. */
    static Map<String, List<Hint>> hintsOf(List<String> files) {
        Map<String, List<Hint>> snapshot = new TreeMap<>();
        for (String file : files) snapshot.put(file, hints(open(file)));
        return snapshot;
    }

    /** The 0-based line of the first line of the editor's text that contains {@code text}. */
    static int lineOf(ITextEditor editor, String text) {
        IDocument document = document(editor);
        return ui(() -> {
            for (int line = 0; line < document.getNumberOfLines(); line++) {
                if (lineText(document, line).contains(text)) return line;
            }
            return fail("'" + text + "' is not in " + editor.getTitle());
        });
    }

    private static String lineText(IDocument document, int line) throws Exception {
        return document.get(document.getLineOffset(line), document.getLineLength(line));
    }

    /** The hints on line {@code line} of {@code hints}, in whichever form (above it or inline). */
    static List<String> labelsOn(List<Hint> hints, int line) {
        return hints.stream().filter(h -> h.line() == line).map(Hint::label).toList();
    }

    /**
     * The one inline hint of {@code parameter} (its type and name, as written) on the line containing
     * {@code lineText}, in {@code hints} as they were taken from that editor. The plugin draws it in front of the
     * parameter's name ({@code Cell @Modified cell}), where the editor finds the parameter by its binding.
     */
    static String parameterHint(ITextEditor editor, List<Hint> hints, String lineText, String parameter) {
        int line = lineOf(editor, lineText);
        IDocument document = document(editor);
        int start = ui(() -> lineText(document, line).indexOf(parameter));
        assertTrue(start >= 0, () -> "'" + parameter + "' is not on line " + (line + 1) + " of " + editor.getTitle());
        int column = start + parameter.lastIndexOf(' ') + 1;
        List<Hint> found = hints.stream().filter(h -> h.line() == line && h.column() == column).toList();
        assertEquals(1, found.size(), () -> "hints at " + editor.getTitle() + ":" + (line + 1) + ":" + (column + 1)
                + " in " + hints);
        return found.getFirst().label();
    }

    /** Replace the first {@code from} in the editor's text by {@code to}, as typing would, and save. */
    static void replaceAndSave(ITextEditor editor, String from, String to) {
        ui(() -> {
            IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
            int offset = document.get().indexOf(from);
            assertTrue(offset >= 0, () -> "'" + from + "' is not in " + editor.getTitle());
            document.replace(offset, from.length(), to);
            editor.doSave(new NullProgressMonitor());
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------------- utilities

    /** Run {@code callable} on the UI thread and return its value; an exception or a failed assertion propagates. */
    static <T> T ui(Callable<T> callable) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            try {
                value.set(callable.call());
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        Throwable t = thrown.get();
        if (t instanceof Error error) throw error;
        if (t instanceof RuntimeException runtime) throw runtime;
        if (t != null) throw new IllegalStateException(t);
        return value.get();
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
