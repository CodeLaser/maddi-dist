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

package io.codelaser.maddi.ide.plugin.uitest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRendererBase;
import com.intellij.codeInsight.hints.declarative.impl.views.InlayPresentationEntry;
import com.intellij.codeInsight.hints.declarative.impl.views.InlayPresentationList;
import com.intellij.codeInsight.hints.declarative.impl.views.TextInlayPresentationEntry;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.testFramework.CompilerTester;
import com.intellij.testFramework.LoggedErrorProcessor;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;

import io.codelaser.maddi.ide.plugin.analysis.MaddiAnalysisService;
import io.codelaser.maddi.ide.plugin.analysis.MaddiRunListener;
import io.codelaser.maddi.ide.plugin.settings.HintPlacement;
import io.codelaser.maddi.ide.plugin.settings.InlineHintsMode;
import io.codelaser.maddi.ide.plugin.settings.MaddiSettings;

/**
 * The IntelliJ counterpart of the VS Code and Eclipse UI tests: a project on disk (heavy fixture: the daemon reads the
 * files, and the compiler writes class files), the fixture of {@code src/test/resources/uitest/demo}, the action
 * "Analyze with maddi" as the menu runs it (compile, then analyse on a real daemon), and what the editor then shows:
 * the inlays it holds, the gutter, the highlighting.
 * <p>
 * Not part of {@code test} by default: it needs the daemon's installDist and a JDK 25+, and takes a while.
 * {@code ./gradlew :maddi-intellij:test -PuiTest}.
 */
public abstract class MaddiIdeTestCase extends JavaCodeInsightFixtureTestCase {

    static final String ANALYZE_ACTION = "maddi.AnalyzeProject";
    private static final List<String> FIXTURE = List.of(
            "Box.java", "Cell.java", "Counter.java", "Names.java", "Point.java", "Shelf.java");
    private static final int RUN_TIMEOUT_SECONDS = 600;

    private CompilerTester compilerTester;
    private MaddiSettings.State savedSettings;
    private AccessToken loggedErrorGuard;

    /**
     * A real JDK gets indexed, javadoc's jquery.js included, which wakes the bundled Vue plugin, whose initialisation
     * fails in a test runtime ("vuejs-plugin/lib/modules should be lib directory"). That logged error has nothing to
     * do with maddi (MaddiConfigBuilderTest meets the same plugin), so it is swallowed; every other one stays fatal.
     */
    private static final LoggedErrorProcessor SWALLOW_BUNDLED_VUE = new LoggedErrorProcessor() {
        @Override
        public @NotNull Set<Action> processError(@NotNull String category, @NotNull String message,
                                                 String @NotNull [] details, @Nullable Throwable t) {
            return fromVue(message, t) ? Action.NONE : Action.ALL;
        }
    };

    private static boolean fromVue(String message, Throwable t) {
        if (message.contains("vuejs")) return true;
        for (Throwable e = t; e != null; e = e.getCause()) {
            if (String.valueOf(e.getMessage()).contains("vuejs") || e.getClass().getName().contains("Vue")) return true;
            for (StackTraceElement s : e.getStackTrace()) {
                if (s.getClassName().contains("Vue") || s.getClassName().contains(".vuejs.")) return true;
            }
        }
        return false;
    }

    /** A hint as the editor holds it: 0-based line; column on that line for an inline hint, -1 for a block one. */
    record Hint(int line, int column, String label) {
        @Override
        public @NotNull String toString() {
            return line + ":" + column + " " + label;
        }
    }

    /** How a run ended, as the run listener heard it: the outcome, and each "phase: message" once, in order. */
    record Run(@Nullable String outcome, @Nullable String failure, List<String> steps) {
    }

    @Override
    protected void tuneFixture(JavaModuleFixtureBuilder<?> moduleBuilder) throws Exception {
        moduleBuilder.addContentRoot(myFixture.getTempDirPath());
        moduleBuilder.addSourceRoot("src");
        moduleBuilder.setLanguageLevel(LanguageLevel.JDK_17);
        // a real JDK to compile against: the test's own runtime (the IDE's JBR); the application does not exist yet
        moduleBuilder.addJdk(System.getProperty("java.home"));
    }

    @Override
    protected void setUp() throws Exception {
        loggedErrorGuard = LoggedErrorProcessor.executeWith(SWALLOW_BUNDLED_VUE); // before the project opens
        super.setUp();
        compilerTester = new CompilerTester(myFixture.getModule()); // the output path, and a build process
        MaddiSettings.State settings = MaddiSettings.getInstance().getState();
        savedSettings = copy(settings);
        settings.jdkHome = jdkHome();
        settings.daemonXmxMb = 2048;
        settings.autoAnalyzeOnBuild = false; // one run per action, so that a test waits for the run it started
        settings.inlineHintsMode = InlineHintsMode.ALL;
        settings.hintPlacement = HintPlacement.ABOVE_DECLARATION;
        settings.warnNearMisses = true;
        settings.incrementalReanalysis = false;
        for (String name : FIXTURE) {
            try (InputStream in = MaddiIdeTestCase.class.getResourceAsStream("/uitest/demo/" + name)) {
                assertNotNull("no fixture file " + name, in);
                myFixture.addFileToProject("src/demo/" + name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (savedSettings != null) MaddiSettings.getInstance().loadState(savedSettings);
            if (compilerTester != null) compilerTester.tearDown();
        } catch (Throwable t) {
            addSuppressedException(t);
        } finally {
            try {
                super.tearDown();
            } finally {
                if (loggedErrorGuard != null) loggedErrorGuard.finish();
            }
        }
    }

    /** MADDI_JDK_HOME, else the test task's maddi.test.jdkHome (the Gradle daemon's JDK, 25+). */
    static String jdkHome() {
        String env = System.getenv("MADDI_JDK_HOME");
        if (env != null && !env.isBlank()) return env;
        String property = System.getProperty("maddi.test.jdkHome", "");
        assertFalse("set MADDI_JDK_HOME to a JDK 25 or later", property.isBlank());
        return property;
    }

    // ------------------------------------------------------------------------------------------------- the run

    /**
     * Open {@code file} and run "Analyze with maddi" in its editor, as the menu does: the project is compiled, then
     * analysed in the background. Waits for the run to end, with events dispatched, then highlights the file (which
     * computes the inlays). Fails on a failed run.
     */
    Run analyze(String file) {
        open(file);
        List<String> steps = new ArrayList<>();
        String[] outcome = {null};
        String[] failure = {null};
        boolean[] ended = {false};
        var connection = getProject().getMessageBus().connect(getTestRootDisposable());
        connection.subscribe(MaddiRunListener.TOPIC, new MaddiRunListener() {
            @Override
            public void statusUpdated(@NotNull String phase, @NotNull String message) {
                String step = message.isEmpty() ? phase : phase + ": " + message;
                if (!steps.contains(step)) steps.add(step);
            }

            @Override
            public void runFailed(@NotNull String kind, @NotNull String message) {
                failure[0] = kind + ": " + message;
                ended[0] = true;
            }

            @Override
            public void runFinished(@Nullable String runOutcome, int findings, int elements, int parseErrors,
                                    long elapsedMillis) {
                outcome[0] = runOutcome;
                ended[0] = true;
            }
        });
        try {
            AnAction action = ActionManager.getInstance().getAction(ANALYZE_ACTION);
            assertNotNull("no action " + ANALYZE_ACTION, action);
            myFixture.testAction(action);
            PlatformTestUtil.waitWithEventsDispatching(() -> "the analysis did not end; steps so far: " + steps,
                    () -> ended[0] && !MaddiAnalysisService.getInstance(getProject()).isRunning(), RUN_TIMEOUT_SECONDS);
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); // the result's refresh
        } finally {
            connection.disconnect();
        }
        assertNull("the run failed: " + failure[0], failure[0]);
        return new Run(outcome[0], failure[0], List.copyOf(steps));
    }

    // ---------------------------------------------------------------------------------------------- the editor

    VirtualFile file(String name) {
        VirtualFile file = myFixture.findFileInTempDir("src/demo/" + name);
        assertNotNull("no " + name, file);
        return file;
    }

    Editor open(String name) {
        myFixture.openFileInEditor(file(name));
        return myFixture.getEditor();
    }

    /**
     * The maddi hints the editor of {@code name} holds after highlighting: the declarative inlays, as the platform
     * draws them, read from the editor's inlay model. Sorted by place.
     */
    List<Hint> hints(String name) {
        Editor editor = open(name);
        myFixture.doHighlighting();
        Document document = editor.getDocument();
        List<Hint> hints = new ArrayList<>();
        int length = document.getTextLength();
        for (Inlay<?> inlay : editor.getInlayModel().getInlineElementsInRange(0, length)) {
            String text = text(inlay);
            if (text != null) {
                int line = document.getLineNumber(inlay.getOffset());
                hints.add(new Hint(line, inlay.getOffset() - document.getLineStartOffset(line), text));
            }
        }
        for (Inlay<?> inlay : editor.getInlayModel().getBlockElementsInRange(0, length)) {
            String text = text(inlay);
            if (text != null) hints.add(new Hint(document.getLineNumber(inlay.getOffset()), -1, text));
        }
        hints.sort(Comparator.comparing(Hint::toString));
        return hints;
    }

    /** The text of a maddi hint; null for an inlay that is not a declarative one, or not maddi's (no '@'). */
    private static @Nullable String text(Inlay<?> inlay) {
        if (!(inlay.getRenderer() instanceof DeclarativeInlayRendererBase<?> renderer)) return null;
        StringBuilder sb = new StringBuilder();
        for (InlayPresentationList list : renderer.getPresentationLists()) {
            for (InlayPresentationEntry entry : list.getEntries()) {
                if (entry instanceof TextInlayPresentationEntry text) sb.append(text.getText());
            }
        }
        String text = sb.toString().strip();
        return text.contains("@") ? text : null;
    }

    Map<String, List<Hint>> hintsOf(List<String> names) {
        Map<String, List<Hint>> snapshot = new TreeMap<>();
        for (String name : names) snapshot.put(name, hints(name));
        return snapshot;
    }

    /** The 0-based line of the first line of {@code name} containing {@code text}. */
    int lineOf(String name, String text) {
        Document document = FileDocumentManager.getInstance().getDocument(file(name));
        assertNotNull(document);
        for (int line = 0; line < document.getLineCount(); line++) {
            if (lineText(document, line).contains(text)) return line;
        }
        fail("'" + text + "' is not in " + name);
        return -1;
    }

    private static String lineText(Document document, int line) {
        return document.getText().substring(document.getLineStartOffset(line), document.getLineEndOffset(line));
    }

    static List<String> labelsOn(List<Hint> hints, int line) {
        return hints.stream().filter(h -> h.line() == line).map(Hint::label).toList();
    }

    /**
     * The inline hint of {@code parameter} (type and name as written) on the line of {@code name} that contains
     * {@code lineText}, in {@code hints} as they were taken from that file; empty when the filter leaves it none.
     * IntelliJ draws it in front of the parameter's type ({@code @Modified Cell cell}).
     */
    String parameterHint(String name, List<Hint> hints, String lineText, String parameter) {
        int line = lineOf(name, lineText);
        Document document = FileDocumentManager.getInstance().getDocument(file(name));
        assertNotNull(document);
        int column = lineText(document, line).indexOf(parameter);
        assertTrue("'" + parameter + "' is not on line " + (line + 1) + " of " + name, column >= 0);
        List<Hint> found = hints.stream().filter(h -> h.line() == line && h.column() == column).toList();
        assertTrue("hints at " + name + ":" + (line + 1) + ":" + (column + 1) + " in " + hints, found.size() <= 1);
        return found.isEmpty() ? "" : found.getFirst().label();
    }

    /** Replace the first {@code from} in {@code name} by {@code to}, as typing would, and save. */
    void replaceAndSave(String name, String from, String to) {
        Document document = FileDocumentManager.getInstance().getDocument(file(name));
        assertNotNull(document);
        int offset = document.getText().indexOf(from);
        assertTrue("'" + from + "' is not in " + name, offset >= 0);
        WriteCommandAction.runWriteCommandAction(getProject(),
                () -> document.replaceString(offset, offset + from.length(), to));
        FileDocumentManager.getInstance().saveAllDocuments();
    }

    private static MaddiSettings.State copy(MaddiSettings.State s) throws IOException {
        MaddiSettings.State c = new MaddiSettings.State();
        c.jdkHome = s.jdkHome;
        c.daemonInstallDir = s.daemonInstallDir;
        c.daemonXmxMb = s.daemonXmxMb;
        c.autoAnalyzeOnBuild = s.autoAnalyzeOnBuild;
        c.showGuardFindings = s.showGuardFindings;
        c.inlineHintsMode = s.inlineHintsMode;
        c.hintPlacement = s.hintPlacement;
        c.showGutterIcons = s.showGutterIcons;
        c.warnNearMisses = s.warnNearMisses;
        c.kotlinFrontEndDir = s.kotlinFrontEndDir;
        c.offerKotlinDownload = s.offerKotlinDownload;
        c.incrementalReanalysis = s.incrementalReanalysis;
        return c;
    }
}
