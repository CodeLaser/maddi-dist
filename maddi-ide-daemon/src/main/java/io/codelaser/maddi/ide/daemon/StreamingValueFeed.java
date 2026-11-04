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

package io.codelaser.maddi.ide.daemon;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.codelaser.maddi.analysis.api.AnalysisValueFeed;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.ide.daemon.AnalyzeHandler.StatusSink;

/**
 * Turns the analyzer's {@link AnalysisValueFeed} into {@code partialResult} frames, so the IDE can show
 * established values while the run is still going. The engine half of this is in
 * {@code maddi-modification-analyzer}; this is the daemon adapter.
 * <p>
 * Why it is worth doing: most of the output is decided in the first pass, and the tail is long in duration
 * but short in decisions. Waiting for the terminal {@code result} means waiting mostly on elements the user
 * is not looking at.
 * <p>
 * Two constraints the feed imposes, both honoured here:
 * <ul>
 *   <li>the {@code analyzed} collection is valid only for the duration of the call, so the frame is built
 *       and handed to the sink inside it, never retained;</li>
 *   <li>the callback runs on the coordinator thread with the workers quiescent, which is what makes reading
 *       {@code info.analysis()} safe — so this must not hand the collection to another thread to process
 *       later. Writing to the socket from here is fine: {@code DaemonMain.send} is synchronized, and the
 *       heartbeat thread already writes concurrently.</li>
 * </ul>
 * Feed exceptions never disturb the analysis (the engine swallows and logs them), but a failure here would
 * cost the user their streamed values silently, so it is logged on this side too.
 */
class StreamingValueFeed implements AnalysisValueFeed {

    private static final Logger LOGGER = LoggerFactory.getLogger(StreamingValueFeed.class);

    private final StatusSink status;
    private final String requestId;
    private final ResultCollector collector;

    /**
     * How the run ended, or null while it is still going. Written on the coordinator thread that also drives
     * {@link #passCompleted}; volatile because {@link #outcome()} is read afterwards from the analysis thread.
     */
    private volatile Phase terminalPhase;

    /*
     Progress, for the heartbeat: the pass running and how many of its elements are done, or the stage between
     passes. Written on the coordinator thread (the counter on the workers), read on the heartbeat thread.
     */
    private final AtomicInteger elementsDone = new AtomicInteger();
    private volatile String pass;
    private volatile int passElements;
    private volatile String stage;

    StreamingValueFeed(StatusSink status, String requestId, ResultCollector collector) {
        this.status = status;
        this.requestId = requestId;
        this.collector = collector;
    }

    @Override
    public void passCompleted(int iteration, boolean fullPass, Collection<Info> analyzed) {
        if (analyzed == null || analyzed.isEmpty()) return;
        List<DaemonProtocol.ElementAnnotation> elements = collector.collectForElements(analyzed);
        if (elements.isEmpty()) return; // the pass touched nothing worth displaying
        LOGGER.debug("pass {} ({}): streaming {} element(s)", iteration,
                fullPass ? "full" : "worklist", elements.size());
        // always false in practice: the terminal phases arrive AFTER the last passCompleted, so certainty
        // reaches the front-end on the terminal result (see outcome()) rather than on a partial frame. The
        // field stays because a future engine that certified mid-run would have somewhere to say so.
        status.partialResult(new DaemonProtocol.PartialResult(
                requestId, iteration, fullPass, terminalPhase == Phase.TERMINAL_CERTIFIED, elements));
    }

    @Override
    public void passStarted(int iteration, int maxIterations, String kind, int elements, int totalElements) {
        elementsDone.set(0);
        passElements = elements;
        // the cap, not an estimate: most runs certify well below it, and a re-derivation raises it
        pass = "pass " + iteration + " of at most " + maxIterations + " (" + kind + ")";
        stage = null;
        status.status(new DaemonProtocol.Status(requestId, "analyze", progress(null), 0, elements));
    }

    @Override
    public void elementCompleted() {
        elementsDone.incrementAndGet();
    }

    @Override
    public void stage(String description) {
        stage = description;
        status.status(new DaemonProtocol.Status(requestId, "analyze", progress(null), null, null));
    }

    /**
     * The heartbeat's frame: the pass and its element count, or the stage that runs after it, then the heap.
     * Before the first pass, the analysis is still setting up.
     */
    DaemonProtocol.Status heartbeat(String heap) {
        String stageNow = stage;
        boolean inPass = pass != null && stageNow == null;
        return new DaemonProtocol.Status(requestId, "analyze", progress(heap),
                inPass ? elementsDone.get() : null, inPass ? passElements : null);
    }

    private String progress(String heap) {
        String passNow = pass;
        String stageNow = stage;
        String what;
        if (passNow == null) {
            what = stageNow != null ? stageNow : "modification analysis (setting up)";
        } else if (stageNow != null) {
            what = stageNow + ", after " + passNow;
        } else {
            what = passNow + ": " + Math.min(elementsDone.get(), passElements) + " / " + passElements + " elements";
        }
        return heap == null ? what : what + " · " + heap;
    }

    @Override
    public void phase(Phase phase, int iteration) {
        if (phase != Phase.CYCLE_BREAKING_ACTIVATED) terminalPhase = phase;
        // Surfaced as status too, so a progress line can show the run ending as it happens.
        status.status(new DaemonProtocol.Status(requestId, "analyze",
                "pass " + iteration + ": " + phase, null, null));
    }

    /**
     * How the run ended, for the terminal result: {@code CERTIFIED} means the values are final, the other
     * outcomes mean best-available. {@code UNKNOWN} when no terminal phase arrived at all — which happens on
     * a run that never got as far as the fixpoint, and is not the same as "not certified".
     */
    String outcome() {
        Phase phase = terminalPhase;
        if (phase == null) return DaemonProtocol.OUTCOME_UNKNOWN;
        // the engine's "TERMINAL_" prefix is an artefact of the enum being shared with CYCLE_BREAKING_ACTIVATED
        return phase.name().startsWith("TERMINAL_") ? phase.name().substring("TERMINAL_".length()) : phase.name();
    }
}
