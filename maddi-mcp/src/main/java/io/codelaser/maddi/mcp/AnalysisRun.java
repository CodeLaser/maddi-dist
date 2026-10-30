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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codelaser.maddi.ide.client.AnalysisModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        }
        notifyAll();
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
        endNanos = System.nanoTime();
        notifyAll();
    }

    synchronized void fail(String message) {
        if (state != State.RUNNING) return;
        error = message;
        state = State.FAILED;
        endNanos = System.nanoTime();
        notifyAll();
    }

    /** Called before the daemon is killed, so the failure the kill causes reads as the stop it is. */
    synchronized void stop() {
        if (state != State.RUNNING) return;
        state = State.STOPPED;
        endNanos = System.nanoTime();
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
        out.put("elapsedSeconds", ((state == State.RUNNING ? System.nanoTime() : endNanos) - startNanos) / 1_000_000_000L);
        if (state == State.RUNNING && lastStatus != null) {
            Map<String, Object> progress = new LinkedHashMap<>();
            for (String field : List.of("phase", "message", "typesDone", "typesTotal")) {
                JsonNode value = lastStatus.get(field);
                if (value != null && !value.isNull()) progress.put(field, value.isNumber() ? value.asInt() : value.asText());
            }
            out.put("progress", progress);
        }
        if (lastIteration >= 0 && state != State.FINISHED) out.put("passesStreamed", lastIteration + 1);
        if (error != null) out.put("error", error);
        out.putAll(Verdicts.certaintyOf(certainty(), state == State.STOPPED));
        if (result != null) out.put("summary", Verdicts.summary(result));
        return out;
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
