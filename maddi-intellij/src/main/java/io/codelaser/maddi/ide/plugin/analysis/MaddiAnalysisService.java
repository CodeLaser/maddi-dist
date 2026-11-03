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

package io.codelaser.maddi.ide.plugin.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import io.codelaser.maddi.ide.client.MaddiDaemonProcess;
import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.ide.plugin.settings.MaddiConfigurable;
import io.codelaser.maddi.ide.plugin.settings.MaddiJdks;
import io.codelaser.maddi.ide.plugin.settings.MaddiSettings;

import io.codelaser.maddi.ide.plugin.ui.MaddiFindingAnchors;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Owns the daemon for a project, runs whole-project analyses off the EDT, caches the latest result
 * indexed by source-file path, and publishes a refresh so the display surfaces repaint. All four
 * surfaces read from the single cached result here.
 */
@Service(Service.Level.PROJECT)
public final class MaddiAnalysisService implements Disposable {
    private static final Logger LOG = Logger.getInstance(MaddiAnalysisService.class);
    private static final String NOTIFICATION_GROUP = "maddi";

    private final Project project;
    private final MaddiDaemonProcess daemon = new MaddiDaemonProcess();
    private final AtomicInteger requestCounter = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean(false);
    // set by stopAnalysis(): the run that then fails was stopped, it did not fail
    private volatile boolean stopRequested;
    // the running analysis has sent its request, so the daemon reads the class files from here on: a build that ends
    // later may not be in this run. A trigger before it is covered by the run (the manual action's make fires the
    // compilation listener as well as its own callback, for one build); a trigger after it asks for a run afterwards.
    private volatile boolean inputsRead;
    private final AtomicBoolean rerunWanted = new AtomicBoolean(false);
    // when the last run that ended with a result started (System.currentTimeMillis), 0 for none: a file saved
    // after it is not in that run's model
    private volatile long lastCompleteRunStarted;
    // when the run that produced the shown result started reading the project (System.currentTimeMillis), 0 when
    // unknown: a file not modified since, and not edited unsaved, holds the text the result's positions are about
    private volatile long runReadingSince;
    private volatile long indexedRunStarted;
    private final MaddiFindingAnchors findingAnchors = new MaddiFindingAnchors();

    private volatile AnalysisModel.Result latest;
    private volatile String daemonInstall = "";
    private volatile String daemonBuild = "unknown";
    // keyed by absolute source-file path (scheme stripped), so VirtualFile.getPath() lookups match
    private volatile Map<String, List<AnalysisModel.Finding>> findingsByPath = Map.of();
    private volatile Map<String, List<AnalysisModel.ElementAnnotation>> annotationsByPath = Map.of();
    // what the last analysis knows of library types, by qualified name: asked for when a library file is on screen,
    // cleared by every result. An empty list: asked, and nothing to show (an unknown type, an older daemon).
    private final Map<String, List<AnalysisModel.LibraryElement>> libraryByType = new ConcurrentHashMap<>();
    private final Set<String> libraryWanted = ConcurrentHashMap.newKeySet();

    public MaddiAnalysisService(Project project) {
        this.project = project;
    }

    public static MaddiAnalysisService getInstance(Project project) {
        return project.getService(MaddiAnalysisService.class);
    }

    /**
     * Kick off a whole-project analysis with a progress indicator. Coalesces: a trigger while a run is going starts no
     * second one (the daemon serves one request at a time). ⛔ Such a trigger used to be dropped: a build that ended
     * while a run was reading the project was never analysed (#1). It now asks for one more run when this one ends,
     * unless it came before the run had read its inputs, which makes it part of this run.
     */
    public void analyzeInBackground() {
        analyzeInBackground(null);
    }

    /**
     * As {@link #analyzeInBackground()}, then {@code afterResult} (on a pooled thread) when the run ends with a result:
     * not when it fails or is stopped. False when a run was already going, and nothing was started.
     */
    public boolean analyzeInBackground(Runnable afterResult) {
        if (!running.compareAndSet(false, true)) {
            if (inputsRead) {
                rerunWanted.set(true);
                LOG.info("maddi analysis already running; analysing again when it ends");
            } else {
                LOG.info("maddi analysis already running and not yet reading the project; this trigger is part of it");
            }
            return false;
        }
        stopRequested = false;
        inputsRead = false;
        rerunWanted.set(false);
        long started = System.currentTimeMillis();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "maddi: analyzing", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                // the progress bar's cancel button: nothing in the run looks at the indicator while it waits for the
                // daemon, so watch it from beside the run
                java.util.concurrent.Future<?> cancelWatch = ApplicationManager.getApplication().executeOnPooledThread(
                        () -> watchCancel(indicator));
                try {
                    analyze(indicator);
                } catch (Exception e) {
                    if (stopRequested) {
                        LOG.info("maddi analysis stopped on request");
                        publishFailure("stopped", "stopped on request; the editor shows what the run had"
                                                  + " established by then");
                        return;
                    }
                    LOG.warn("maddi analysis failed", e);
                    // The balloon is transient and the tool window used to learn nothing at all from this
                    // path, so a failed run was indistinguishable from a stale successful one (#29).
                    publishFailure(e.getClass().getSimpleName(),
                            e.getMessage() == null ? e.toString() : e.getMessage());
                    String message = e.getMessage() == null ? e.toString() : e.getMessage();
                    notifyFailure("Analysis failed: " + message.lines().findFirst().orElse("")
                                  + ". Details in the maddi tool window.");
                } finally {
                    cancelWatch.cancel(true);
                    inputsRead = false;
                    running.set(false);
                    if (!stopRequested) fetchLibrary(); // what was asked for during the run
                }
                if (afterResult != null && !stopRequested && lastCompleteRunStarted >= started) afterResult.run();
                // a stopped run is not followed by another: stopping means stop
                if (rerunWanted.getAndSet(false) && !stopRequested) analyzeInBackground();
            }
        });
        return true;
    }

    /** When the last run that ended with a result started, in milliseconds; 0 when there has been none. */
    public long lastCompleteRunStarted() {
        return lastCompleteRunStarted;
    }

    /** The running daemon, for requests on the last run's model; started by an analysis. */
    public MaddiDaemonProcess daemon() {
        return daemon;
    }

    private void analyze(ProgressIndicator indicator) throws Exception {
        MaddiSettings.State settings = MaddiSettings.getInstance().getState();
        // the settings' JDK, else the IDE's default JDK when it is 25 or later, else the newest registered one
        String jdkHome = ReadAction.compute(() -> MaddiJdks.effectiveHome(settings));
        if (jdkHome.isEmpty()) {
            jdkHome = System.getProperty("maddi.jdk.home", "").trim(); // dev fallback (runIde)
        }
        if (jdkHome.isEmpty()) {
            publishFailure("configuration", "no JDK 25+ is set in Settings → Tools → maddi, and the IDE has none");
            notifyUser("maddi needs a JDK 25 or later: choose or download one in Settings → Tools → maddi.",
                    NotificationType.WARNING);
            return;
        }
        indicator.setText("maddi: starting daemon");
        Path logFile = daemonLogFile();
        Path installDir = resolveInstallDir(settings);
        if (!installDir.equals(bundledDaemonDir()) && !Files.isDirectory(installDir.resolve("bin"))) {
            // an override left over from a development setup: say which setting to clear, not "launcher not found"
            publishFailure("configuration", "the daemon install override names " + installDir
                                            + ", which holds no maddi daemon");
            notifyFailure("The daemon install override in Settings → Tools → maddi names " + installDir
                          + ", which holds no maddi daemon. Clear it to use the daemon bundled with the plugin.");
            return;
        }
        // null: no Kotlin front end yet; the daemon then analyses the Java half and names the skipped Kotlin
        Path kotlinFrontEnd = MaddiKotlinFrontEnd.home(settings, installDir);
        String kotlinProblem = kotlinFrontEnd == null || settings.kotlinFrontEndDir == null
                               || settings.kotlinFrontEndDir.isBlank() ? null : MaddiKotlinFrontEnd.problemWith(kotlinFrontEnd);
        if (kotlinProblem != null) {
            // said here, where the setting is, before the daemon's own account of the skipped Kotlin sources
            notifyUser("The Kotlin front end override in Settings → Tools → maddi does not work: " + kotlinProblem
                       + ". Kotlin sources are not analysed until it names a directory of the K2 jars.",
                    NotificationType.WARNING);
        }
        daemon.ensureStarted(installDir, Path.of(jdkHome), settings.daemonXmxMb, kotlinFrontEnd, logFile);
        // WHICH daemon answered, in idea.log: the bundled one is a build, not a version, and a stale bundle
        // presents as an analyzer regression (2026-08-24). The stamp is the source state; see DaemonMain.
        LOG.info("maddi daemon: install=" + installDir + ", build=" + daemon.buildStamp()
                 + ", kotlin=" + (kotlinFrontEnd == null ? "none" : kotlinFrontEnd));
        this.daemonInstall = installDir.toString();
        this.daemonBuild = daemon.buildStamp();

        indicator.setText("maddi: building configuration");
        String resolvedJdkHome = jdkHome;
        boolean warnNearMisses = settings.warnNearMisses;
        AnalysisModel.AnalyzeConfig config = ReadAction.compute(
                () -> new MaddiConfigBuilder().build(project, resolvedJdkHome, warnNearMisses));
        if (kotlinFrontEnd == null) {
            MaddiKotlinFrontEnd.offerIfNeeded(project, config, installDir, this::analyzeInBackground);
        }
        String requestId = "req-" + requestCounter.incrementAndGet();
        publishRun(l -> l.runStarted(requestId, daemonInstall, daemonBuild));
        long runStarted = System.currentTimeMillis(); // the daemon reads the files from here on
        runReadingSince = runStarted;
        inputsRead = true;

        JsonNode node = daemon.analyze(requestId, config, frame -> onStreamedFrame(indicator, frame));
        if ("error".equals(node.path("type").asText())) {
            String kind = node.path("kind").asText("error");
            String message = node.path("message").asText("");
            publishFailure(kind, message);
            notifyUser("Daemon error: " + message, NotificationType.ERROR);
            return;
        }
        AnalysisModel.Result result =
                daemon.client().objectMapper().treeToValue(node, AnalysisModel.Result.class);
        applyResult(result);
        lastCompleteRunStarted = runStarted;
        publishRun(l -> l.runFinished(result.outcome(), result.findings().size(),
                result.elementAnnotations().size(), result.parseErrorCount(), result.elapsedMillis()));
        // Silence on a certified run; a run that stopped at the iteration cap or on a plateau produces
        // annotations indistinguishable from final ones, so that is worth one line.
        // A partial parse is reported separately and takes precedence: "some files did not parse" tells the
        // user what to fix, where "no fixpoint" would send them looking at the analyser instead.
        if (AnalysisModel.isPartialParse(result)) {
            notifyUser(result.parseErrorCount() + " file(s) did not parse; the analysis covers the rest of the"
                       + " project. Properties may be weaker than the code allows, because references from the"
                       + " missing files were not seen. See the maddi tool window for the parse errors.",
                    NotificationType.WARNING);
        } else if (AnalysisModel.certaintyOf(result) == AnalysisModel.Certainty.BEST_AVAILABLE) {
            notifyUser("Analysis finished without reaching a fixpoint: the results are the best available,"
                       + " not certified. Some properties may be weaker than the code allows.",
                    NotificationType.WARNING);
        }
    }

    /**
     * A non-terminal frame from the daemon: either progress, or values the analysis has established so far.
     * The latter are displayed immediately, so a large project annotates the editor while it is still
     * running instead of staying blank until the end.
     */
    private void onStreamedFrame(ProgressIndicator indicator, JsonNode frame) {
        if (AnalysisModel.PARTIAL_RESULT.equals(frame.path("type").asText())) {
            try {
                AnalysisModel.PartialResult partial =
                        daemon.client().objectMapper().treeToValue(frame, AnalysisModel.PartialResult.class);
                // merged, not replaced: one frame is one analysis pass, and values only ever strengthen
                AnalysisModel.Result merged = AnalysisModel.merge(latest, partial);
                applyResult(merged);
                // what has been decided, not a percentage: the run is a fixpoint iteration whose length is
                // not known in advance, so a fraction would be invented
                indicator.setText2("pass " + partial.iteration() + ", "
                                   + merged.elementAnnotations().size() + " element(s) so far");
                publishRun(l -> l.passCompleted(partial.iteration(), partial.fullPass(),
                        merged.elementAnnotations().size()));
            } catch (Exception e) {
                // losing a streamed frame costs an early glimpse, never the run
                LOG.warn("could not read a streamed result", e);
            }
            return;
        }
        String phase = frame.path("phase").asText("analyzing");
        indicator.setText("maddi: " + phase);
        String message = frame.path("message").asText("");
        // the heartbeat carries the message during the long analysis phase; it is what shows the run is alive
        if (!message.isEmpty()) indicator.setText2(message);
        publishRun(l -> l.statusUpdated(phase, message));
    }

    /**
     * Index a result and refresh the display surfaces. Public so tests (and any future non-daemon result
     * source) can drive the UI with a canned result: {@link #index} runs synchronously, so the surface
     * lookups ({@link #findingsForPath}/{@link #annotationsForPath}) are populated on return.
     */
    public void applyResult(AnalysisModel.Result result) {
        index(result);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            recomputeHints("maddi analysis result");
            project.getMessageBus().syncPublisher(MaddiResultListener.TOPIC).resultUpdated(result);
        });
    }

    private void index(AnalysisModel.Result result) {
        Map<String, List<AnalysisModel.Finding>> fb = new HashMap<>();
        for (AnalysisModel.Finding f : result.findings()) {
            String path = pathOf(f.uri());
            if (path != null) fb.computeIfAbsent(path, k -> new ArrayList<>()).add(f);
        }
        Map<String, List<AnalysisModel.ElementAnnotation>> ab = new HashMap<>();
        for (AnalysisModel.ElementAnnotation e : result.elementAnnotations()) {
            String path = pathOf(e.uri());
            if (path != null) ab.computeIfAbsent(path, k -> new ArrayList<>()).add(e);
        }
        this.latest = result;
        findingAnchors.clear();
        indexedRunStarted = runReadingSince;
        this.findingsByPath = fb;
        this.annotationsByPath = ab;
        libraryByType.clear();
    }

    public List<AnalysisModel.Finding> findingsForPath(String path) {
        return findingsByPath.getOrDefault(path, List.of());
    }

    /**
     * Where a finding of {@code file} is in its document now, following the edits made since it was first shown
     * ({@link MaddiFindingAnchors}); {@code null} when its text is gone.
     */
    public @Nullable TextRange findingRange(AnalysisModel.Finding finding, VirtualFile file, Document doc) {
        long since = indexedRunStarted;
        boolean asAnalysed = since > 0 && !FileDocumentManager.getInstance().isDocumentUnsaved(doc)
                             && file.getTimeStamp() <= since;
        return findingAnchors.range(finding, doc, asAnalysed);
    }

    public List<AnalysisModel.ElementAnnotation> annotationsForPath(String path) {
        return annotationsByPath.getOrDefault(path, List.of());
    }

    /**
     * The annotations to show in a file: a project file's, by path, placed on its declarations as they are now; a
     * library file's (Set.java from the JDK's sources), matched to its declarations by signature, asked of the daemon
     * the first time the file is shown after an analysis.
     */
    public List<AnalysisModel.ElementAnnotation> annotationsFor(PsiFile file, Document doc) {
        VirtualFile vf = file.getVirtualFile();
        if (vf == null) return List.of();
        List<AnalysisModel.ElementAnnotation> own = annotationsForPath(vf.getPath());
        if (!own.isEmpty()) {
            // on the declarations as they are now, not where the analysed source had them
            return file instanceof PsiJavaFile javaFile ? MaddiLibraryPlacement.follow(javaFile, doc, own) : own;
        }
        if (latest == null || !(file instanceof PsiJavaFile javaFile)
            || !ProjectFileIndex.getInstance(project).isInLibrary(vf)) {
            return own;
        }
        List<String> types = MaddiLibraryPlacement.typesOf(javaFile);
        List<AnalysisModel.LibraryElement> elements = new ArrayList<>();
        boolean missing = false;
        for (String type : types) {
            List<AnalysisModel.LibraryElement> known = libraryByType.get(type);
            if (known == null) {
                libraryWanted.add(type);
                missing = true;
            } else {
                elements.addAll(known);
            }
        }
        if (missing) ApplicationManager.getApplication().executeOnPooledThread(this::fetchLibrary);
        return elements.isEmpty() ? List.of() : MaddiLibraryPlacement.place(javaFile, doc, vf.getPath(), elements);
    }

    /*
     Asks the daemon for the library types wanted, unless an analysis is running: the daemon serves one request at a
     time, and the run's end asks again. Then has the editor recompute its hints, now with the answers.
     */
    private void fetchLibrary() {
        if (running.get() || libraryWanted.isEmpty() || !daemon.isAlive()) return;
        List<String> types = List.copyOf(libraryWanted);
        libraryWanted.removeAll(types);
        Map<String, List<AnalysisModel.LibraryElement>> answer = new HashMap<>();
        types.forEach(t -> answer.put(t, new ArrayList<>()));
        try {
            JsonNode node = daemon.libraryAnnotations("lib-" + requestCounter.incrementAndGet(), types);
            if (AnalysisModel.LIBRARY_RESULT.equals(node.path("type").asText())) {
                AnalysisModel.LibraryResult result =
                        daemon.client().objectMapper().treeToValue(node, AnalysisModel.LibraryResult.class);
                for (AnalysisModel.LibraryElement e : result.elements()) {
                    // a nested type's elements come with its top-level type, asked for by the latter's name
                    types.stream().filter(t -> e.type().equals(t) || e.type().startsWith(t + ".")).findFirst()
                            .ifPresent(t -> answer.get(t).add(e));
                }
            } else {
                LOG.info("maddi: no library annotations from this daemon: " + node.path("message").asText(""));
            }
        } catch (Exception e) {
            LOG.warn("maddi: library annotations failed", e);
        }
        libraryByType.putAll(answer);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            recomputeHints("maddi library annotations");
        });
    }

    /**
     * Re-run the highlighting passes, inlay hints included. Declarative inlay hints cache on a modification stamp
     * per editor that restart() alone does not invalidate, so a result would only show up after the next edit.
     * scheduleRecompute clears it for one editor and restarts; resetModificationStamp() did all editors at once,
     * but it is internal API, which the Marketplace refuses.
     */
    private void recomputeHints(String reason) {
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (project.equals(editor.getProject())) {
                DeclarativeInlayHintsPassFactory.Companion.scheduleRecompute(editor, project);
            }
        }
        // restart() with no argument is deprecated; the reason shows up in the daemon's own logging. Also covers
        // the gutter icons and findings when no editor is open.
        DaemonCodeAnalyzer.getInstance(project).restart(reason);
    }

    public AnalysisModel.Result latestResult() {
        return latest;
    }

    /** Where the daemon of the last (or current) run came from, and which build it is. */
    public String daemonInstall() {
        return daemonInstall;
    }

    public String daemonBuild() {
        return daemonBuild;
    }

    /**
     * Stop the daemon; the next analysis launches a fresh one. This is the fast loop: point the daemon install
     * directory at {@code maddi-ide-daemon/build/install/maddi-ide-daemon}, run {@code installDist}, restart
     * the daemon, analyze — no plugin rebuild, no reinstall, no IDE restart.
     */
    /**
     * Stop the running analysis: the daemon is killed, which ends the run at once (it is in the middle of a fixpoint
     * iteration that has no other way out), and the next analysis starts a fresh one. What the run streamed so far
     * stays on screen.
     */
    public void stopAnalysis() {
        if (!running.get()) return;
        stopRequested = true;
        rerunWanted.set(false);
        daemon.kill();
        daemonBuild = "unknown";
    }

    public boolean isRunning() {
        return running.get();
    }

    private void watchCancel(ProgressIndicator indicator) {
        try {
            while (running.get()) {
                if (indicator.isCanceled()) {
                    stopAnalysis();
                    return;
                }
                Thread.sleep(250);
            }
        } catch (InterruptedException e) {
            // the run ended
        }
    }

    public void restartDaemon() {
        daemon.restart();
        daemonBuild = "unknown";
        publishRun(l -> l.statusUpdated("daemon", "daemon stopped; the next analysis will start a fresh one"));
    }

    /** Run events are a UI concern: always on the EDT, and never allowed to break the run that reports them. */
    private void publishRun(java.util.function.Consumer<MaddiRunListener> event) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                event.accept(project.getMessageBus().syncPublisher(MaddiRunListener.TOPIC));
            } catch (Exception e) {
                LOG.warn("maddi run listener failed", e);
            }
        });
    }

    private void publishFailure(String kind, String message) {
        publishRun(l -> l.runFailed(kind, message == null ? "" : message));
    }

    private Path resolveInstallDir(MaddiSettings.State settings) {
        if (settings.daemonInstallDir != null && !settings.daemonInstallDir.isBlank()) {
            return Path.of(settings.daemonInstallDir);
        }
        String dev = System.getProperty("maddi.daemon.install", ""); // dev fallback (runIde)
        if (!dev.isBlank()) return Path.of(dev);
        return bundledDaemonDir();
    }

    /**
     * Where the daemon bundled inside this plugin lives, {@code <plugin>/daemon} (M4 packaging).
     * <p>
     * ⛔ DELIBERATELY NO PLATFORM API. The obvious calls — {@code PluginManagerCore.getPlugin(PluginId)},
     * {@code PluginManager.getPluginByClass} and in fact every descriptor lookup on {@code PluginManager} —
     * are all {@code @ApiStatus.Internal} as of 2026.2, and the plugin verifier reports each one; the
     * Marketplace approval guidelines say a plugin may not violate internal APIs. Our own class file is
     * loaded from {@code <plugin>/lib/maddi-intellij-<version>.jar}, so the plugin directory is two levels
     * up, and reading that is plain JDK.
     * <p>
     * The fallback covers a null code source (possible under a different class loader): the plugin
     * directory name is fixed by {@code intellijPlatform.projectName = "maddi"} in the build.
     */
    private static Path bundledDaemonDir() {
        try {
            CodeSource codeSource = MaddiAnalysisService.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                Path jar = Path.of(codeSource.getLocation().toURI());
                Path lib = jar.getParent();
                if (lib != null && lib.getParent() != null) return lib.getParent().resolve("daemon");
            }
        } catch (URISyntaxException | IllegalArgumentException e) {
            LOG.warn("maddi: cannot read own code source, falling back to the plugins path", e);
        }
        return Path.of(PathManager.getPluginsPath(), "maddi", "daemon");
    }

    /** maddi returns {@code file:///abs/File.java}; index/query by the bare path so VirtualFile paths match. */
    private static String pathOf(String uri) {
        if (uri == null) return null;
        try {
            URI parsed = URI.create(uri);
            return parsed.getScheme() == null ? uri : parsed.getPath();
        } catch (IllegalArgumentException e) {
            return uri;
        }
    }

    /** Where the daemon's own output goes: next to idea.log (Help → Show Log in Finder / Explorer). */
    public static Path daemonLogFile() {
        return Path.of(PathManager.getLogPath(), "maddi-daemon.log");
    }

    /** An error balloon that offers the daemon's output, where the reason a run failed usually is. */
    private void notifyFailure(String content) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("maddi", content, NotificationType.ERROR)
                .addAction(NotificationAction.createSimple("Open settings", () ->
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, MaddiConfigurable.class)))
                .addAction(NotificationAction.createSimple("Open daemon log", () -> {
                    VirtualFile log = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(daemonLogFile());
                    // not FileEditorManager.openFile(file, focus): deprecated from 2026.3
                    if (log != null) {
                        new OpenFileDescriptor(project, log).navigate(true);
                    } else {
                        // no daemon has started yet, so there is nothing to open: say so rather than do nothing
                        notifyUser("There is no daemon log yet: no daemon has started. It will be at "
                                   + daemonLogFile() + ".", NotificationType.INFORMATION);
                    }
                }))
                .notify(project);
    }

    private void notifyUser(String content, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("maddi", content, type)
                .notify(project);
    }

    @Override
    public void dispose() {
        daemon.close();
    }
}
