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

/**
 * Seam between the socket transport ({@link DaemonMain}) and the actual analysis
 * ({@link WarmAnalysisService}). Implementations run one whole-project analysis per call.
 */
public interface AnalyzeHandler {

    /** Sink for coarse progress messages emitted during a long analysis. */
    interface StatusSink {
        void status(DaemonProtocol.Status status);

        /**
         * Values established so far, emitted at analysis pass boundaries so a front-end can display them
         * before the run finishes. Default no-op: a transport that does not want them pays nothing, and
         * this stays a functional interface for the callers that only care about status.
         */
        default void partialResult(DaemonProtocol.PartialResult partial) {
            // ignored
        }
    }

    /**
     * Run a whole-project analysis and return a plain-JSON result. May emit {@code status}
     * updates via the sink. Throwing is allowed: the transport turns it into an {@code error}
     * message and keeps the daemon alive.
     */
    DaemonProtocol.Result analyze(DaemonProtocol.AnalyzeProject request, StatusSink status) throws Exception;

    /**
     * What the last analysis knows of the given library types. Default: nothing, every type unknown — a handler
     * that keeps no model.
     */
    default DaemonProtocol.LibraryResult libraryAnnotations(DaemonProtocol.LibraryAnnotations request) {
        return new DaemonProtocol.LibraryResult(request.requestId(), java.util.List.of(), request.types());
    }

    /** The given types in Kotlin. Default: nothing translated, every type unknown — a handler that keeps no model. */
    default DaemonProtocol.TranslateResult translate(DaemonProtocol.Translate request) throws java.io.IOException {
        return new DaemonProtocol.TranslateResult(request.requestId(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), request.types());
    }
}
