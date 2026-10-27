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

import java.util.List;
import java.util.Map;

/**
 * Wire protocol between the IntelliJ plugin (JBR 21) and the maddi analysis daemon (JDK 25+).
 * <p>
 * Transport is a loopback TCP socket carrying newline-delimited JSON (NDJSON): one JSON object
 * per line, UTF-8. Every message is an object with a {@code "type"} discriminator; requests are
 * dispatched on that field, responses are these records serialized with the type added.
 * <p>
 * All payloads are primitives / strings / nested records only — no maddi types leak across the
 * boundary, which is what keeps the plugin safely on JBR 21 while maddi runs on JDK 25.
 */
public final class DaemonProtocol {
    private DaemonProtocol() {
    }

    public static final int PROTOCOL_VERSION = 1;

    // ---- message type discriminators ----
    public static final String T_HANDSHAKE = "handshake";
    public static final String T_HANDSHAKE_ACK = "handshakeAck";
    public static final String T_ANALYZE_PROJECT = "analyzeProject";
    public static final String T_STATUS = "status";
    /**
     * Values established so far, sent zero or more times before the terminal {@link #T_RESULT}. Deliberately
     * NOT a repeatable {@code result}: clients loop until a terminal frame, so a new non-terminal type reaches
     * them as an ordinary streamed frame, while a second {@code result} would be mistaken for the whole run.
     */
    public static final String T_PARTIAL_RESULT = "partialResult";
    public static final String T_RESULT = "result";
    public static final String T_ERROR = "error";
    public static final String T_PING = "ping";
    public static final String T_PONG = "pong";
    public static final String T_CANCEL = "cancel";
    public static final String T_SHUTDOWN = "shutdown";
    public static final String T_BYE = "bye";

    // ---- responses (serialized; DaemonMain adds the "type" field) ----

    /**
     * @param buildStamp the source state the daemon was built from ({@code DaemonMain#BUILD_STAMP}); the only
     *                   field here that distinguishes two builds, since the three versions are constants of the
     *                   source, not of the build. Purely informational — no client behaviour depends on it.
     */
    public record HandshakeAck(int protocolVersion, String daemonVersion, String maddiVersion,
                               String buildStamp) {
    }

    public record Pong(long nowNanos) {
    }

    /** Coarse progress for a long analysis. phase ∈ initialize|parse|prep|order|analyze|collect. */
    public record Status(String requestId, String phase, String message,
                         Integer typesDone, Integer typesTotal) {
    }

    /** Daemon stays alive after an error. kind ∈ parse|analyze|internal|busy|bad-request. */
    public record ErrorMsg(String requestId, String kind, String message) {
    }

    // ---- analysis request/result (payloads used from M1 onwards) ----

    /** One source or test root fed to maddi. */
    public record SourceRoot(String name, String path, boolean test) {
    }

    /** One classpath entry: a jar or a (hot) compiler-output directory. scope ∈ compile|test|runtime|test-runtime. */
    public record ClasspathEntry(String path, String scope) {
    }

    /**
     * One compiled unit of the project — a maddi source set — with ITS OWN class path and ITS OWN direct
     * dependencies, named.
     * <p>
     * This is the shape the javac-log route produces and the flat {@link AnalyzeConfig#sources()} /
     * {@link AnalyzeConfig#classpath()} pair cannot express. With the flat pair, {@code InputConfiguration}'s
     * string-style builder wires every source set to ALL class-path parts and all earlier source sets: on the
     * CodeLaser tree that was 160 x 491 = 75,520 (set, entry) pairs against the 5,524 the javac-log
     * configuration declares, and javac opens a ZipFileSystem per container per source set.
     *
     * @param name             stable identity, e.g. {@code my-module/main}; what {@link #dependencies} refer to
     * @param sourceDirectories roots parsed from source
     * @param outputPath       where this unit's class files live. ⚠ It is the set's IDENTITY, not a class-path
     *                         entry: listing a module's own output on some other module's class path is what
     *                         makes one FQN reachable as both source and bytecode
     * @param test             a test source set
     * @param sourceRelease    {@code --release} for this unit; 0 when unknown (then the JDK maddi runs on is
     *                         used, which is dangerous rather than merely imprecise — see InputConfiguration)
     * @param dependencies     names of the OTHER source sets this one compiles against. Direct only:
     *                         {@code SourceSetImpl.recursiveDependencies} computes the closure itself
     * @param classPath        jars and class directories for THIS unit alone, including any provided /
     *                         compileOnly scope, which the whole-project union route drops
     */
    public record ModuleSourceSet(String name,
                                  List<String> sourceDirectories,
                                  String outputPath,
                                  boolean test,
                                  int sourceRelease,
                                  List<String> dependencies,
                                  List<String> classPath) {
    }

    public record AnalyzeConfig(String workingDirectory,
                                String sdkHome,
                                String sourceEncoding,
                                List<String> jmods,
                                List<SourceRoot> sources,
                                List<ClasspathEntry> classpath,
                                List<String> restrictToPackages,
                                boolean parallel,
                                boolean warnNearMisses,
                                List<ModuleSourceSet> moduleSourceSets) {

        /**
         * The flat form: no per-module structure, so the assembler falls back to the string-style builder and
         * its auto-wiring. Kept for front-ends (and fixtures) that have not been moved over, and for a client
         * older than this field — where Jackson leaves {@code moduleSourceSets} null.
         */
        public AnalyzeConfig(String workingDirectory, String sdkHome, String sourceEncoding, List<String> jmods,
                             List<SourceRoot> sources, List<ClasspathEntry> classpath,
                             List<String> restrictToPackages, boolean parallel, boolean warnNearMisses) {
            this(workingDirectory, sdkHome, sourceEncoding, jmods, sources, classpath, restrictToPackages,
                    parallel, warnNearMisses, List.of());
        }
    }

    public record AnalyzeProject(String requestId, AnalyzeConfig config) {
    }

    /** A located finding (guard violation, crash, parse error) with a recursive why-chain. */
    public record Finding(String uri,
                          Integer beginLine, Integer beginCol, Integer endLine, Integer endCol,
                          String severity, String category, String message,
                          List<Finding> causes) {
    }

    /**
     * One rendered annotation with the metadata the inline-hints filter needs.
     * polarity ∈ POSITIVE (proven-stronger: @NotModified/@Immutable/@Container/@Independent/@NotNull/@Final/
     * @UnmodifiableView where the result is not immutable, @FinalFields, and @Modified(hc=true): only the hidden
     * content is modified), NEGATIVE (baseline:
     * @Modified/@Mutable/@Dependent/@Nullable, and @StaticSideEffects), NEUTRAL (@GetSet/@Identity/@Fluent/…),
     * EVENTUAL (after-mark verdicts, see AnnotationTagger).
     * contextDefault = the value is implied by the enclosing declaration (a container's @NotModified params,
     * a plain method's @Modified, …), so it can be hidden as clutter; surprising values (a @Modified param in a
     * @Container) are NOT context defaults.
     */
    public record Annotation(String text, String polarity, boolean contextDefault) {
    }

    /** Computed analysis for one declaration. kind ∈ TYPE|METHOD|FIELD|PARAMETER. */
    public record ElementAnnotation(String uri,
                                    Integer beginLine, Integer beginCol, Integer endLine, Integer endCol,
                                    String kind,
                                    String fqn,
                                    List<String> displayAnnotations,
                                    List<Annotation> annotations,
                                    Map<String, String> properties) {
    }

    /**
     * How the fixpoint iteration ended, which decides whether the values in a result are final.
     * <ul>
     *   <li>{@code CERTIFIED} — the fixpoint was reached: the values are final.</li>
     *   <li>{@code MAX_ITERATIONS} — stopped at the iteration cap: best available, not certified.</li>
     *   <li>{@code PLATEAU} — stopped on the oscillation plateau: best available, not certified.</li>
     *   <li>{@code UNKNOWN} — the run produced no terminal phase (e.g. it ended at parse errors).</li>
     * </ul>
     * Worth surfacing rather than assuming: a value that stopped at the cap looks exactly like a certified
     * one, and only this says otherwise.
     */
    public static final String OUTCOME_CERTIFIED = "CERTIFIED";
    public static final String OUTCOME_UNKNOWN = "UNKNOWN";

    public record Result(String requestId,
                         List<Finding> findings,
                         List<ElementAnnotation> elementAnnotations,
                         List<String> initializationProblems,
                         int parseErrorCount,
                         int hintsLoaded,
                         long elapsedMillis,
                         String outcome) {
    }

    /**
     * Analysis values established after one pass of the fixpoint iteration, for display before the run ends.
     * Safe to show: values are write-once and refine monotonically, so nothing here is ever retracted — a
     * later frame (or the terminal {@code result}) can only strengthen what a previous one said.
     * <p>
     * Carries annotations only, no findings: guard findings are computed once, after the fixpoint, so there
     * is nothing partial to report about them.
     * <p>
     * {@code elements} over-approximates what changed — the whole analysis order on the first pass, the
     * shrinking dirty set afterwards — so a consumer merges by element rather than treating a frame as a
     * complete picture. {@code certain} marks the run's values as final (the fixpoint was certified); until
     * then a displayed value is established but may still strengthen.
     */
    public record PartialResult(String requestId,
                                int iteration,
                                boolean fullPass,
                                boolean certain,
                                List<ElementAnnotation> elements) {
    }
}
