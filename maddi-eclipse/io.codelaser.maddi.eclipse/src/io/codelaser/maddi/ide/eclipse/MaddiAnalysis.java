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

import com.fasterxml.jackson.databind.JsonNode;
import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.ide.client.MaddiDaemonProcess;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a whole-project analysis on the shared daemon, off the UI thread, and surfaces the result (markers +
 * findings view). Shared by the manual {@link AnalyzeMaddiHandler} and the {@link MaddiBuildListener}
 * auto-trigger. Coalesces: while one run is in flight, further triggers are dropped (the daemon is
 * single-request, and re-running on every incremental build would only pile up).
 * <p>
 * Every way a run ends is recorded in {@link MaddiRun}, which the findings view shows. A manual run that fails
 * also returns an error status, which Eclipse shows as a dialog, and opens the view: the Error Log alone is a place
 * nobody looks.
 */
public final class MaddiAnalysis {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    // set by stop(): the run that then fails was stopped, it did not fail
    private static volatile boolean stopRequested;

    private MaddiAnalysis() {
    }

    /** True while a run is in flight; the build listener ignores the builds a run itself starts. */
    public static boolean isRunning() {
        return RUNNING.get();
    }

    /**
     * Schedule an analysis of {@code javaProject}. {@code reveal} opens the findings view and reports a
     * failure as a dialog — appropriate for a manual run, not for a background build trigger.
     */
    public static void schedule(IJavaProject javaProject, boolean reveal) {
        Job job = new Job("maddi: analyzing " + javaProject.getElementName()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                // qualified: inside a Job, RUNNING is Job's own state constant
                if (!MaddiAnalysis.RUNNING.compareAndSet(false, true)) return Status.OK_STATUS; // one at a time
                stopRequested = false;
                try {
                    return analyze(javaProject, reveal, monitor);
                } catch (Exception e) {
                    if (stopRequested) return stopped(javaProject);
                    String message = e.getMessage() == null ? e.toString() : e.getMessage();
                    return failed(javaProject, reveal, e.getClass().getSimpleName(), message, e);
                } finally {
                    MaddiAnalysis.RUNNING.set(false);
                    if (!stopRequested) MaddiLibrary.get().fetchWanted(); // what was asked for during the run
                }
            }

            // the Progress view's stop: nothing in the run looks at the monitor while it waits for the daemon
            @Override
            protected void canceling() {
                stop();
            }
        };
        job.setUser(reveal); // manual runs show foreground progress; build triggers stay quiet
        job.schedule();
    }

    /** The project of the last run, analysed again; false when there has been none. */
    public static boolean rerun() {
        String name = MaddiRun.get().lastProject();
        if (name.isEmpty()) return false;
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (!project.exists() || !project.isOpen()) return false;
        schedule(JavaCore.create(project), true);
        return true;
    }

    /**
     * Stop the running analysis: the daemon is killed, which ends the run at once (it is in the middle of a fixpoint
     * iteration that has no other way out), and the next analysis starts a fresh one. What the run streamed so far
     * stays on screen.
     */
    public static void stop() {
        if (!RUNNING.get()) return;
        stopRequested = true;
        MaddiEclipsePlugin.get().daemon().kill();
    }

    private static IStatus stopped(IJavaProject javaProject) {
        MaddiRun.get().failed(javaProject.getElementName(), "stopped",
                "stopped on request; the editor shows what the run had established by then");
        return Status.CANCEL_STATUS;
    }

    /** Stop the daemon; the next analysis launches a fresh one (after installDist on a daemon install override). */
    public static void restartDaemon() {
        MaddiEclipsePlugin.get().daemon().restart();
        MaddiRun.get().daemonStopped();
    }

    /** Said when the command finds no Java project to analyse; on the UI thread. */
    public static void reportNoProject() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        MessageDialog.openInformation(window == null ? null : window.getShell(), "maddi",
                "Select a Java project, or open one of its files, then choose Analyze with maddi.");
    }

    /** Where the daemon's own output goes: the plugin's state directory, under the workspace's .metadata. */
    public static Path daemonLogFile() {
        return MaddiEclipsePlugin.get().getStateLocation().append("maddi-daemon.log").toPath();
    }

    private static IStatus analyze(IJavaProject javaProject, boolean reveal, IProgressMonitor monitor)
            throws Exception {
        // The total is not knowable up front — the analysis is a fixpoint iteration, not a fixed number of
        // steps — so the task is indeterminate and the phases arrive as subtasks.
        SubMonitor progress = SubMonitor.convert(monitor, "maddi", IProgressMonitor.UNKNOWN);
        String jdkHome = MaddiPreferences.jdkHome(); // the preference, else the default installed JDK 25+
        if (jdkHome == null) {
            return failed(javaProject, reveal, "configuration", "maddi needs a JDK 25 or later: add one under"
                    + " Window → Preferences → Java → Installed JREs, or choose one in Window → Preferences → maddi.",
                    null);
        }
        Path install = MaddiDaemonInstall.resolve(); // configured dir, else the copy bundled in the plugin
        if (install == null) {
            return failed(javaProject, reveal, "configuration", "this build of the plugin has no daemon bundled;"
                    + " set a daemon install directory in Window → Preferences → maddi.", null);
        }

        // fresh class files first, as IntelliJ compiles before it analyses: the parser resolves against them
        progress.subTask("building " + javaProject.getElementName());
        build(javaProject, progress);

        progress.subTask("starting the analysis daemon");
        MaddiDaemonProcess daemon = MaddiEclipsePlugin.get().daemon();
        daemon.ensureStarted(install, Path.of(jdkHome), MaddiPreferences.daemonXmxMb(), daemonLogFile());
        // WHICH daemon answered: the bundled one is a build, not a version, and a stale bundle presents as an
        // analyzer regression rather than as a stale bundle (2026-08-24). See DaemonMain's build stamp.
        MaddiEclipsePlugin.info("maddi daemon: install=" + install + ", build=" + daemon.buildStamp());

        progress.subTask("building the project model");
        AnalysisModel.AnalyzeConfig config = new MaddiEclipseConfigBuilder()
                .build(javaProject, jdkHome, MaddiPreferences.warnNearMisses());
        MaddiRun.get().started(javaProject.getElementName(), install.toString(), daemon.buildStamp(),
                !MaddiResults.get().findings().isEmpty());
        if (reveal) revealFindingsView();
        // Streamed frames land here: values established by each analysis pass, so the editor is annotated
        // while the run continues rather than only when it ends, and the progress text says what is
        // happening. Everything that is not the terminal frame arrives through this consumer.
        JsonNode node = daemon.analyze("req-" + System.nanoTime(), config,
                frame -> onStreamedFrame(daemon, frame, progress));
        if ("error".equals(node.path("type").asText())) {
            return failed(javaProject, reveal, node.path("kind").asText("error"),
                    "daemon error: " + node.path("message").asText(""), null);
        }
        AnalysisModel.Result result = daemon.client().objectMapper().treeToValue(node, AnalysisModel.Result.class);

        ResourcesPlugin.getWorkspace().run(
                m -> MaddiMarkers.apply(ResourcesPlugin.getWorkspace().getRoot(), result),
                null);
        MaddiResults.get().update(result);
        MaddiRun.get().finished(result);
        // Silence on a certified run; a run that stopped at the iteration cap or on a plateau produces
        // annotations indistinguishable from final ones, so that is worth a line. A partial parse takes
        // precedence: "some files did not parse" says what to fix, "no fixpoint" would point at the analyzer.
        if (AnalysisModel.isPartialParse(result)) {
            warn(result.parseErrorCount() + " file(s) did not parse; the analysis covers the rest of the project."
                 + " Properties may be weaker than the code allows, because references from the missing files"
                 + " were not seen. The parse errors are in the maddi Findings view.");
        } else if (AnalysisModel.certaintyOf(result) == AnalysisModel.Certainty.BEST_AVAILABLE) {
            warn("Analysis finished without reaching a fixpoint: the results are the best available, not"
                 + " certified. Some properties may be weaker than the code allows.");
        }
        return Status.OK_STATUS;
    }

    /**
     * Build the project and those it requires, so that their output folders hold current class files. Under
     * auto-build Eclipse is already doing that, so wait for it instead of starting a second build.
     */
    private static void build(IJavaProject javaProject, SubMonitor progress) throws Exception {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        if (workspace.isAutoBuilding()) {
            Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, progress);
            return;
        }
        for (IJavaProject project : MaddiEclipseConfigBuilder.withRequiredProjects(javaProject)) {
            project.getProject().build(IncrementalProjectBuilder.INCREMENTAL_BUILD, progress);
        }
    }

    /**
     * Record a run that ended without a result. A manual run gets an error status, which Eclipse shows as a
     * dialog; a build-triggered one only the view and the Error Log, since nobody asked for it.
     */
    private static IStatus failed(IJavaProject javaProject, boolean reveal, String kind, String message,
                                  Throwable t) {
        MaddiRun.get().failed(javaProject.getElementName(), kind, message);
        IStatus status = new Status(IStatus.ERROR, MaddiEclipsePlugin.PLUGIN_ID,
                "maddi analysis failed: " + message.lines().findFirst().orElse("")
                + "\nThe maddi Findings view has the details; the daemon's own output is in " + daemonLogFile(), t);
        if (!reveal) {
            MaddiEclipsePlugin.get().getLog().log(status);
            return Status.OK_STATUS;
        }
        revealFindingsView();
        return status; // the job manager shows it, and logs it
    }

    private static void warn(String message) {
        MaddiRun.get().status("warning", message);
        MaddiEclipsePlugin.log(IStatus.WARNING, "maddi: " + message, null);
    }

    /**
     * A non-terminal frame from the daemon: either progress, or values established so far.
     * <p>
     * Values are merged into what is already shown rather than replacing it — one frame is the elements of
     * one analysis pass. Markers are deliberately left alone: {@link MaddiMarkers#apply} rebuilds the whole
     * workspace's markers, far too heavy per pass, and findings are not partial anyway. Hints do update,
     * because they read {@link MaddiResults} through the code-mining provider.
     * <p>
     * Both kinds also move the progress text and the run log. Without it an analysis of a large project is a
     * spinner with nothing behind it for minutes, which is indistinguishable from a hang.
     */
    private static void onStreamedFrame(MaddiDaemonProcess daemon, JsonNode frame, SubMonitor progress) {
        if (!AnalysisModel.PARTIAL_RESULT.equals(frame.path("type").asText())) {
            // a status frame: phase transitions, plus a heartbeat every couple of seconds during the long
            // analysis phase, which is what makes "still running" visible at all
            String phase = frame.path("phase").asText("");
            String message = frame.path("message").asText("");
            if (!phase.isEmpty() || !message.isEmpty()) {
                progress.subTask(message.isEmpty() ? phase : phase + ": " + message);
                MaddiRun.get().status(phase.isEmpty() ? "analyzing" : phase, message);
            }
            return;
        }
        try {
            AnalysisModel.PartialResult partial =
                    daemon.client().objectMapper().treeToValue(frame, AnalysisModel.PartialResult.class);
            AnalysisModel.Result merged = MaddiResults.get().mergePartial(partial);
            // report what has been decided, not a percentage: the run is a fixpoint iteration whose length
            // is not known in advance, so a fraction would be invented
            progress.subTask("analyzing — pass " + partial.iteration() + ", "
                             + merged.elementAnnotations().size() + " element(s) so far");
            MaddiRun.get().pass(partial.iteration(), partial.fullPass(), merged.elementAnnotations().size());
        } catch (Exception e) {
            // a frame we could not read costs the user an early glimpse, never the run
            MaddiEclipsePlugin.log(IStatus.WARNING, "maddi: could not read a streamed result", e);
        }
    }

    private static void revealFindingsView() {
        Display.getDefault().asyncExec(() -> {
            try {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage page = window == null ? null : window.getActivePage();
                if (page != null) page.showView(MaddiFindingsView.ID, null, IWorkbenchPage.VIEW_VISIBLE);
            } catch (Exception e) {
                MaddiEclipsePlugin.error("could not open the maddi findings view", e);
            }
        });
    }
}
