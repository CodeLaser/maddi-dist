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

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What a run is DOING, as opposed to what it produced ({@link MaddiResults} holds the values): the Eclipse
 * counterpart of the IntelliJ plugin's {@code MaddiRunListener} and of the header and run log of its tool window.
 * <p>
 * ⛔ A RUN THAT ENDS BADLY MUST REACH THE SAME SURFACE AS ONE THAT ENDS WELL. Every terminal path is recorded here,
 * a failure as much as a result: otherwise a failed run leaves the previous run's findings on screen with nothing
 * marking them stale, and its error only in the Error Log (GitHub #29 in IntelliJ).
 * <p>
 * Kept here rather than in the view, so that a view opened mid-run, or after it, still shows the whole story.
 * Updated from the analysis job; listeners marshal to the UI thread themselves.
 */
public final class MaddiRun {

    private static final MaddiRun INSTANCE = new MaddiRun();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Bounded so a long run cannot grow the log without limit; oldest lines go first. */
    private static final int MAX_LOG_LINES = 5_000;

    /**
     * @param terminal how the last run ended, empty while none has; a failure starts with "FAILED"
     * @param stale    the findings on screen are the PREVIOUS run's, because this one has not ended yet
     */
    public record State(boolean running, long startedNanos, String phase, String message, String terminal,
                        boolean stale, String project, String daemonInstall, String daemonBuild) {
        public boolean failed() {
            return terminal.startsWith("FAILED");
        }
    }

    private State state = new State(false, 0, "", "", "", false, "", "", "");
    private final Deque<String> log = new ArrayDeque<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private MaddiRun() {
    }

    public static MaddiRun get() {
        return INSTANCE;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized String logText() {
        return String.join("\n", log) + (log.isEmpty() ? "" : "\n");
    }

    /** The project of the last run, which the view's Analyze action runs again; empty before any. */
    public synchronized String lastProject() {
        return state.project;
    }

    public void started(String project, String daemonInstall, String daemonBuild, boolean findingsOnScreen) {
        synchronized (this) {
            state = new State(true, System.nanoTime(), "starting", "", "", findingsOnScreen, project, daemonInstall,
                    daemonBuild);
            append("--- " + project + " started, daemon " + daemonBuild + " at " + daemonInstall);
        }
        notifyListeners();
    }

    /** A phase transition or a heartbeat: the daemon is alive and this is what it is doing. */
    public void status(String phase, String message) {
        synchronized (this) {
            state = new State(state.running, state.startedNanos, phase,
                    message.isEmpty() ? state.message : message, state.terminal, state.stale, state.project,
                    state.daemonInstall, state.daemonBuild);
            append(message.isEmpty() ? phase : phase + ": " + message);
        }
        notifyListeners();
    }

    /** One analysis pass completed, with the number of elements decided so far (monotonic). */
    public void pass(int iteration, boolean fullPass, int elementsSoFar) {
        status(state().phase, "pass " + iteration + (fullPass ? " (full)" : "") + ", " + elementsSoFar
                              + " element(s) decided");
    }

    /** The run ended without a result: a configuration problem, a daemon error, or an exception here. */
    public void failed(String project, String kind, String message) {
        synchronized (this) {
            String first = message.lines().findFirst().orElse("");
            state = new State(false, state.startedNanos, state.phase, state.message,
                    "FAILED (" + kind + "): " + first, state.stale, project, state.daemonInstall, state.daemonBuild);
            append("ERROR [" + kind + "] " + message);
        }
        notifyListeners();
    }

    /** The run ended with a result. */
    public void finished(AnalysisModel.Result result) {
        synchronized (this) {
            String certainty = String.valueOf(AnalysisModel.certaintyOf(result));
            String terminal = result.findings().size() + " finding(s), " + result.elementAnnotations().size()
                              + " annotated element(s)"
                              + (result.parseErrorCount() > 0 ? ", " + result.parseErrorCount()
                                                                + " file(s) did NOT parse" : "")
                              + " — outcome " + (result.outcome() == null ? "UNKNOWN" : result.outcome())
                              + " (" + certainty + "), " + result.elapsedMillis() + " ms";
            state = new State(false, state.startedNanos, state.phase, state.message, terminal, false,
                    state.project, state.daemonInstall, state.daemonBuild);
            append("--- finished: " + terminal);
        }
        notifyListeners();
    }

    public void daemonStopped() {
        synchronized (this) {
            state = new State(state.running, state.startedNanos, state.phase, state.message, state.terminal,
                    state.stale, state.project, "", "");
            append("daemon stopped on request; the next analysis will start a fresh one");
        }
        notifyListeners();
    }

    public void clearLog() {
        synchronized (this) {
            log.clear();
        }
        notifyListeners();
    }

    private void append(String line) {
        log.addLast(LocalTime.now().format(CLOCK) + "  " + line);
        while (log.size() > MAX_LOG_LINES) log.removeFirst();
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) listener.run();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }
}
