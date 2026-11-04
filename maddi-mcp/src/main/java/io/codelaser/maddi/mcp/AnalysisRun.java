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

package io.codelaser.maddi.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.codelaser.maddi.ide.client.AnalysisModel;

/**
 * One analysis of a project, from the request to its terminal frame. A run takes minutes on a large project, longer
 * than a tool call should block, so the server starts it on a thread of its own and answers from here: the last
 * status frame, and the values streamed so far ({@code partialResult} frames, merged by element), so verdicts can be
 * asked while the run goes on, as {@link AnalysisModel.Certainty#PROVISIONAL}.
 */
public final class AnalysisRun {

    public enum State {RUNNING, FINISHED, FAILED, STOPPED}

    private final String id;
    private final String project;
    private final Map<String, Object> scope;
    private final long startNanos = System.nanoTime();
    private State state = State.RUNNING;
    private AnalysisModel.Result result;
    private JsonNode lastStatus;
    private int lastIteration = -1;
    private String error;
    private long endNanos;
    private String configuration;
    private Process process;
    private String finishedAt;
    private long restoredElapsedSeconds = -1;
    // what an incremental daemon said it did, from the status frames: re-parsed, seeded; null for a cold run
    private String reparsed;
    private String seeded;
    private boolean reloaded;

    /**
     * A run as it is stored once it has ended, to be queried and compared after a restart. Only the result counts:
     * the daemon's model of the run is gone with the daemon.
     */
    public record Snapshot(String id, String project, Map<String, Object> scope, State state, String configuration,
                           long elapsedSeconds, int passesStreamed, String finishedAt, AnalysisModel.Result result,
                           String incremental) {
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(id, project, scope, state, configuration, elapsedSeconds(), lastIteration + 1,
                finishedAt, result, incremental());
    }

    public static AnalysisRun restore(Snapshot snapshot) {
        AnalysisRun run = new AnalysisRun(snapshot.id(), snapshot.project(),
                snapshot.scope() == null ? Map.of() : snapshot.scope());
        run.state = snapshot.state();
        run.result = snapshot.result();
        run.configuration = snapshot.configuration();
        run.lastIteration = snapshot.passesStreamed() - 1;
        run.finishedAt = snapshot.finishedAt();
        run.restoredElapsedSeconds = snapshot.elapsedSeconds();
        run.restoredIncremental = snapshot.incremental();
        return run;
    }

    /** When the run ended, as an ISO instant; null while it runs. */
    public synchronized String finishedAt() {
        return finishedAt;
    }

    private long elapsedSeconds() {
        if (restoredElapsedSeconds >= 0) return restoredElapsedSeconds;
        return ((state == State.RUNNING ? System.nanoTime() : endNanos) - startNanos) / 1_000_000_000L;
    }

    private void ended() {
        endNanos = System.nanoTime();
        finishedAt = java.time.Instant.now().toString();
    }

    public AnalysisRun(String id, String project) {
        this(id, project, Map.of());
    }

    /** @param scope the part of the project analysed: the source sets and packages asked for; empty for all */
    public AnalysisRun(String id, String project, Map<String, Object> scope) {
        this.id = id;
        this.project = project;
        this.scope = scope;
    }

    public Map<String, Object> scope() {
        return scope;
    }

    public String id() {
        return id;
    }

    public String project() {
        return project;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized AnalysisModel.Result result() {
        return result;
    }

    /** A non-terminal frame: a {@code status} heartbeat, or a {@code partialResult} folded into what is known. */
    synchronized void onFrame(JsonNode frame, ObjectMapper mapper) {
        if (AnalysisModel.PARTIAL_RESULT.equals(frame.path("type").asText())) {
            try {
                AnalysisModel.PartialResult partial = mapper.treeToValue(frame, AnalysisModel.PartialResult.class);
                result = AnalysisModel.merge(result, partial);
                lastIteration = partial.iteration();
            } catch (Exception e) {
                // a frame this client cannot read is a value missed, not a run lost: the terminal result has all
            }
        } else {
            lastStatus = frame;
            noteIncremental(frame.path("phase").asText(""), frame.path("message").asText(""));
        }
        notifyAll();
    }

    private String restoredIncremental;

    /*
     An incremental daemon says what it does in its status frames: "reload" first, then "parse: re-parsing N changed
     type(s)" and "analyze: modification analysis, seeded with M type(s)". A reload that falls back to a cold run
     starts over with "initialize".
     */
    private void noteIncremental(String phase, String message) {
        switch (phase) {
            case "reload" -> reloaded = true;
            case "initialize" -> {
                reloaded = false;
                reparsed = null;
                seeded = null;
            }
            case "parse" -> {
                if (reloaded && message.startsWith("re-parsing ")) reparsed = message.substring("re-parsing ".length());
            }
            case "analyze" -> {
                if (reloaded && message.contains("seeded with ")) {
                    seeded = message.substring(message.indexOf("seeded with ") + "seeded with ".length());
                }
            }
            default -> {
            }
        }
    }

    /** What the incremental daemon did for this run, in words; null for a cold run. */
    public synchronized String incremental() {
        if (restoredIncremental != null) return restoredIncremental;
        if (state != State.FINISHED || !reloaded) return null;
        // the daemon returns the previous result as it was, its outcome included
        if (reparsed == null) return "no source changed since the previous run: its result stands";
        return "re-analysed after an edit, on the previous run's state: re-parsed " + reparsed
               + (seeded == null ? "" : ", analysis seeded with " + seeded);
    }

    /** A step before the analysis proper (checking or regenerating the configuration), as a status frame would say. */
    synchronized void phase(String phase, String message) {
        lastStatus = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("phase", phase).put("message", message);
        notifyAll();
    }

    /** What became of the stored configuration: used as it was, regenerated, or used although stale. */
    synchronized void configuration(String note) {
        configuration = note;
    }

    /** The process this run waits for before the analysis, which {@link #stop()} must end too; null when done. */
    synchronized void attach(Process process) {
        this.process = process;
    }

    /** The terminal frame: {@code result} or {@code error}. */
    synchronized void onEnd(JsonNode frame, ObjectMapper mapper) {
        if ("result".equals(frame.path("type").asText())) {
            try {
                result = mapper.treeToValue(frame, AnalysisModel.Result.class);
                state = State.FINISHED;
            } catch (Exception e) {
                fail("cannot read the daemon's result: " + e.getMessage());
                return;
            }
        } else {
            error = frame.path("kind").asText("error") + ": " + frame.path("message").asText(frame.toString());
            state = State.FAILED;
        }
        ended();
        notifyAll();
    }

    /** A run asked only to bring the configuration up to date has done so: finished, without values. */
    synchronized void configured() {
        if (state != State.RUNNING) return;
        state = State.FINISHED;
        ended();
        notifyAll();
    }

    synchronized void fail(String message) {
        if (state != State.RUNNING) return;
        error = message;
        state = State.FAILED;
        ended();
        notifyAll();
    }

    /** Called before the daemon is killed, so the failure the kill causes reads as the stop it is. */
    synchronized void stop() {
        if (state != State.RUNNING) return;
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        state = State.STOPPED;
        ended();
        notifyAll();
    }

    /** Wait for the run to end, or for {@code millis}; true when it ended. */
    synchronized boolean awaitEnd(long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (state == State.RUNNING) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return false;
            wait(left);
        }
        return true;
    }

    /**
     * How far the values may be trusted. A stopped or running run's values hold, but may still have strengthened:
     * provisional. A finished one: what the outcome says (see {@link AnalysisModel#certaintyOf}).
     */
    public synchronized AnalysisModel.Certainty certainty() {
        return switch (state) {
            case FINISHED -> AnalysisModel.certaintyOf(result);
            case RUNNING, STOPPED -> result == null ? AnalysisModel.Certainty.UNKNOWN
                    : AnalysisModel.Certainty.PROVISIONAL;
            case FAILED -> AnalysisModel.Certainty.UNKNOWN;
        };
    }

    /** The run as a tool answers it. */
    public synchronized Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", id);
        out.put("project", project);
        out.put("state", state.name());
        if (!scope.isEmpty()) out.put("scope", scope);
        out.put("elapsedSeconds", elapsedSeconds());
        if (finishedAt != null) out.put("finishedAt", finishedAt);
        if (state == State.RUNNING && lastStatus != null) {
            Map<String, Object> progress = new LinkedHashMap<>();
            for (String field : List.of("phase", "message", "typesDone", "typesTotal")) {
                JsonNode value = lastStatus.get(field);
                if (value != null && !value.isNull()) progress.put(field, value.isNumber() ? value.asInt() : value.asText());
            }
            out.put("progress", progress);
        }
        if (configuration != null) out.put("configuration", configuration);
        if (lastIteration >= 0 && state != State.FINISHED) out.put("passesStreamed", lastIteration + 1);
        if (error != null) out.put("error", error);
        String incremental = incremental();
        if (incremental != null) out.put("incremental", incremental);
        out.putAll(Verdicts.certaintyOf(certainty(), state == State.STOPPED, incrementalOutcome()));
        if (result != null) out.put("summary", Verdicts.summary(result));
        return out;
    }

    /** Whether the result is an incremental one, which is never certified. */
    public synchronized boolean incrementalOutcome() {
        return result != null && AnalysisModel.OUTCOME_INCREMENTAL.equals(result.outcome());
    }

    /** Progress as a fraction for an MCP progress notification, when the daemon gave a type count. */
    synchronized double[] progress() {
        if (lastStatus == null) return null;
        int done = lastStatus.path("typesDone").asInt(-1);
        int total = lastStatus.path("typesTotal").asInt(-1);
        return done >= 0 && total > 0 ? new double[]{done, total} : null;
    }

    synchronized String progressMessage() {
        if (lastStatus == null) return "starting";
        String phase = lastStatus.path("phase").asText("");
        String message = lastStatus.path("message").asText("");
        return message.isEmpty() ? phase : phase + ": " + message;
    }
}
