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

import io.codelaser.maddi.analysis.api.AnalysisEngine;
import io.codelaser.maddi.analysis.api.AnalysisEngines;
import io.codelaser.maddi.analysis.api.AnalysisProblem;
import io.codelaser.maddi.analysis.api.ModificationOptions;
import io.codelaser.maddi.analysis.api.ModificationRequest;
import io.codelaser.maddi.analysis.api.PrepOutcome;
import io.codelaser.maddi.analysis.api.PrepRequest;
import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.callgraph.ComputeCallGraph;
import io.codelaser.maddi.cst.api.analysis.Message;
import io.codelaser.maddi.cst.api.element.ModuleInfo;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.api.parser.ParseResult;
import io.codelaser.maddi.inspection.api.parser.Summary;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.inspection.mixed.MixedProjectInspector;
import io.codelaser.maddi.inspection.resource.DetectKotlinSources;
import io.codelaser.maddi.inspection.openjdk.JavaInspectorImpl;
import io.codelaser.maddi.kotlin.api.PlaceholderCensus;
import io.codelaser.maddi.kotlin.realm.K2Realm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs a whole-project maddi analysis per request, on a warm JVM. A <b>fresh</b> inspector is built each
 * request so analysis state is clean (the process, not the inspector, is what stays warm — no spawn / JIT
 * cost). Ports {@code RunAnalyzer.runAnalyzer()} (parse → prep → order → analyze), with two IDE-oriented
 * differences: {@code failFast=false} (partial projects still yield findings) and results collected into plain
 * JSON rather than written to disk.
 * <p>
 * Two parse paths share everything after the parse. A Java-only project takes the openjdk inspector, as it
 * always has. A project with Kotlin sources takes {@link MixedProjectInspector} — the path of
 * {@code RunMixedPrepAnalyzer} — when the Kotlin front end can be installed ({@link K2Realm}: the compiler's
 * jars are not on this JVM's classpath but found through {@code -Dmaddi.k2.home} / {@code -Dmaddi.k2.classpath});
 * otherwise it falls back to the Java half, and says so.
 */
public class WarmAnalysisService implements AnalyzeHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(WarmAnalysisService.class);

    private final InputConfigurationAssembler assembler = new InputConfigurationAssembler();
    /** The static-side-effect analysis for this service's runs; null keeps the analyzer's default. */
    private final Boolean staticSideEffects;

    public WarmAnalysisService() {
        this(null);
    }

    /** For tests: switch the static-side-effect analysis on or off for every run of this service. */
    WarmAnalysisService(Boolean staticSideEffects) {
        this.staticSideEffects = staticSideEffects;
    }

    /** What either parse path hands to the shared tail. */
    private record Parsed(JavaInspector inspector,
                          Runtime runtime,
                          SourceSet sourceSetOfRequest,
                          Collection<TypeInfo> primaryTypes,
                          Collection<ModuleInfo> moduleInfos,
                          Summary summary,
                          boolean partial,
                          int hints) {
    }

    /*
     The last run's model, kept for the library types the editor asks about (libraryAnnotations), and dropped as the
     next run starts, so that two models never live at once. Requests come one at a time, on the connection's thread,
     which keeps maddi's runtime on one thread as the analysis requires.
     */
    private record LastRun(Runtime runtime, SourceSet sourceSet, ResultCollector collector) {
    }

    private LastRun lastRun;

    @Override
    public DaemonProtocol.LibraryResult libraryAnnotations(DaemonProtocol.LibraryAnnotations request) {
        LastRun run = lastRun;
        if (run == null) return new DaemonProtocol.LibraryResult(request.requestId(), List.of(), request.types());
        List<DaemonProtocol.LibraryElement> elements = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String fqn : request.types()) {
            TypeInfo typeInfo = run.runtime().getFullyQualified(fqn, false, run.sourceSet());
            if (typeInfo == null) {
                unknown.add(fqn);
            } else {
                elements.addAll(run.collector().collectLibrary(typeInfo));
            }
        }
        return new DaemonProtocol.LibraryResult(request.requestId(), elements, unknown);
    }

    @Override
    public DaemonProtocol.Result analyze(DaemonProtocol.AnalyzeProject request, StatusSink status) throws Exception {
        long start = System.currentTimeMillis();
        lastRun = null;
        String requestId = request.requestId();
        boolean parallel = request.config().parallel();

        emit(status, requestId, "initialize", "building inspector", null, null);
        InputConfiguration inputConfiguration = assembler.build(request.config());
        List<String> initProblems = new ArrayList<>();

        DetectKotlinSources kotlinSources = DetectKotlinSources.in(inputConfiguration);
        String kotlinUnavailable = kotlinSources.found() ? installKotlinFrontEnd() : null;
        Parsed parsed;
        if (kotlinSources.found() && kotlinUnavailable == null) {
            parsed = parseMixed(inputConfiguration, requestId, status, initProblems);
        } else {
            if (kotlinSources.found()) {
                // ⚠ Not a refusal, as the CLI's is: an IDE asking about a mixed project is better served by the
                // Java half plus a visible problem than by nothing at all -- and initProblems is the channel that
                // already carries "your analysis is not what you think it is" to the editor.
                String problem = kotlinSources.fileCount() + " Kotlin source file(s) in "
                                 + kotlinSources.sourceSetNames() + " are NOT analyzed: " + kotlinUnavailable
                                 + " The findings below cover the Java sources alone.";
                LOGGER.error("{}", problem);
                initProblems.add(problem);
            }
            parsed = parseJava(inputConfiguration, parallel, requestId, status, initProblems);
        }
        Summary summary = parsed.summary();

        AnalysisEngine engine = AnalysisEngines.require("the IDE daemon's modification analysis");
        ResultCollector collector = new ResultCollector(engine, parsed.runtime(), parsed.sourceSetOfRequest());

        emit(status, requestId, "prep", "call graph", null, parsed.primaryTypes().size());
        // Fault-tolerant like RunAnalyzer's: on a partial parse a type whose supertype did not resolve is far
        // likelier to trip prep, and one such type must not abort the run for everything else. The isolated
        // failures are reported through the prep outcome's problems below.
        PrepOutcome prep = engine.prep(new PrepRequest(parsed.runtime(), Set.copyOf(parsed.primaryTypes()),
                parsed.moduleInfos(), typeInfo -> false, parallel, true));
        ComputeCallGraph ccg = prep.callGraph();

        emit(status, requestId, "order", "analysis order", null, parsed.primaryTypes().size());
        List<Info> order = new ComputeAnalysisOrder().go(ccg.graph(), parallel);

        emit(status, requestId, "analyze", "modification analysis", 0, order.size());
        ModificationOptions modOptions = new ModificationOptions(
                // as the CLI: a safety net, not a budget. MODREACH re-derives after each of its rounds and adds this
                // many iterations each time; langchain4j-core's first fixpoint alone takes 15
                30,
                // NOT the CLI's plateau early exit: under MODREACH's re-derivations it stopped both demo projects at
                // PLATEAU with the same verdicts the certified run reaches, and the IDE shows the outcome
                null,
                false,  // track object creations: switched on by MODREACH and by nullability, which need it
                // the reachability pass (MODREACH), the CLI's default: the authority over the three modification
                // properties, and what the eventual layer needs to see the honest post-cutover state
                true,
                true,   // fault tolerant: isolate a crash on one element into a finding; don't abort the run
                // advisory "you are one member away from @Container/@Immutable/..." warnings; opt-in, as in
                // RunAnalyzer, because they are noisy on a codebase that has not been curated for them
                request.config().warnNearMisses(),
                false,  // environment gates: the CLI's, not the daemon's
                false,  // store fingerprints
                staticSideEffects,
                null)   // eventual cluster: the analyzer's default
                // the nullability pass after the fixpoint, writing NULLABILITY_* for the @NotNull/@Nullable hints
                .withNullability(true)
                // beside a verdict, the first statement responsible for it, for the "why?" in the gutter tooltip
                .withCulprits(true);
        // Stream what each pass established, so the IDE can annotate the file on screen long before the run
        // ends: the first pass decides most of the output, and the tail is long but decides little.
        StreamingValueFeed valueFeed = new StreamingValueFeed(status, requestId, collector);
        ModificationRequest modRequest = new ModificationRequest(parsed.inspector(), order, null,
                parsed.primaryTypes(), modOptions, valueFeed);
        // analyze() is one long blocking step with no sub-progress; run it on a worker and heartbeat so the
        // client's socket read never times out on a large project. A throw propagates: DaemonMain turns it into
        // an error{}, the daemon survives.
        List<Message> messages = runWithHeartbeat(status, valueFeed, () -> {
            try {
                return engine.modification(modRequest).messages();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        emit(status, requestId, "collect", "collecting results", null, null);
        List<DaemonProtocol.Finding> findings = collector.collectFindings(messages, summary);
        List<DaemonProtocol.ElementAnnotation> elementAnnotations =
                collector.collectElementAnnotations(parsed.primaryTypes());

        long elapsed = System.currentTimeMillis() - start;
        // A partial parse can never be certified: the missing compilation units contribute no references, so a
        // surviving type's properties may be weaker than its code allows. Downgrade rather than let the client
        // read a fixpoint outcome as final. (parseErrorCount on the Result carries the detail; no protocol change.)
        String outcome = parsed.partial() ? DaemonProtocol.OUTCOME_UNKNOWN : valueFeed.outcome();
        List<AnalysisProblem> prepExceptions = prep.problems();
        if (!prepExceptions.isEmpty()) {
            LOGGER.warn("prep isolated {} type(s)/method(s); they were skipped", prepExceptions.size());
        }
        LOGGER.info("analysis complete in {} ms ({}{}): {} findings, {} element annotations",
                elapsed, outcome, parsed.partial() ? ", PARTIAL parse" : "", findings.size(), elementAnnotations.size());
        int parseErrors = summary == null ? 0 : summary.parseExceptions().size();
        lastRun = new LastRun(parsed.runtime(), parsed.sourceSetOfRequest(), collector);
        return new DaemonProtocol.Result(requestId, findings, elementAnnotations, initProblems,
                parseErrors, parsed.hints(), elapsed, outcome);
    }

    /** The Java-only parse: the openjdk inspector, hints loaded before the parse. */
    private static Parsed parseJava(InputConfiguration inputConfiguration, boolean parallel, String requestId,
                                    StatusSink status, List<String> initProblems) throws IOException {
        JavaInspector inspector = new JavaInspectorImpl(true, false); // openjdk inspector (run-openjdk style)
        inspector.initialize(inputConfiguration).stream().map(String::valueOf).forEach(initProblems::add);

        // Eagerly parse the JDK packages whose hints we load (after initialize, so the classpath is set), then
        // load the bundled JDK + library analysis hints so modification/immutability/independence of library
        // types is known, not guessed from shallow defaults. Order mirrors run-openjdk's RunAnalyzer.
        HintsLoader hintsLoader = new HintsLoader();
        hintsLoader.preload(inspector);
        inspector.onlyPreload(); // commit preloaded types so LoadAnalysisResults can resolve them (like CommonTest)

        SourceSet sourceSet = inspector.mainSources();
        if (sourceSet == null) {
            sourceSet = inputConfiguration.sourceSets().stream().findAny().orElse(null);
        }

        emit(status, requestId, "hints", "loading analysis hints", null, null);
        int hints = hintsLoader.loadHints(inspector.runtime(), sourceSet);
        LOGGER.info("preloaded {} primary types of analysis hints", hints);

        JavaInspector.ParseOptions parseOptions = new JavaInspector.ParseOptions.Builder()
                .setDetailedSources(true)      // precise Source positions, required for inline hints
                .setFailFast(false)            // IDE: a project with in-progress errors still yields partial results
                .setParallel(parallel)
                .setLombok(inputConfiguration.containsLombok())
                .build();

        emit(status, requestId, "parse", "parsing sources", null, null);
        Summary summary = inspector.parse(parseOptions);

        // A parse error must not cost the whole project its analysis. This used to return findings-only on
        // haveErrors(), which meant ONE unresolvable module yielded zero annotations everywhere — measured on
        // Pulsar: 344 parse errors out of 98 source roots, and not one element annotation. That also
        // contradicted setFailFast(false) above, which is set precisely so a mid-edit tree still yields
        // something. So: analyse what did parse, and report the run as partial via parseErrorCount.
        boolean partialParse = summary.haveErrors();
        if (partialParse) {
            LOGGER.info("parse produced {} error(s); analysing the {} type(s) that did parse",
                    summary.parseExceptions().size(), summary.types().size());
        }
        ParseResult parseResult = partialParse ? summary.parseResultIgnoringErrors() : summary.parseResult();
        return new Parsed(inspector, inspector.runtime(), sourceSet, parseResult.primaryTypes(),
                parseResult.sourceSetToModuleInfoMap().values(), summary, partialParse, hints);
    }

    /**
     * The mixed Java+Kotlin parse, as {@code RunMixedPrepAnalyzer} does it: one shared core, each type in its
     * own source set, hints loaded AFTER the parse (only by then is the compiled-types manager populated).
     */
    private static Parsed parseMixed(InputConfiguration inputConfiguration, String requestId, StatusSink status,
                                     List<String> initProblems) {
        HintsLoader hintsLoader = new HintsLoader();
        JavaInspector.ParseOptions parseOptions = new JavaInspector.ParseOptions.Builder()
                .setDetailedSources(true)      // precise Source positions, required for inline hints
                .setFailFast(false)            // IDE: a project with in-progress errors still yields partial results
                // ⚠ not parallel: the mixed driver interleaves javac and K2 on one thread
                .setLombok(inputConfiguration.containsLombok())
                .build();
        MixedProjectInspector.Settings settings =
                new MixedProjectInspector.Settings(parseOptions, true, hintsLoader::preload);

        emit(status, requestId, "parse", "parsing Java and Kotlin sources", null, null);
        MixedProjectInspector.Result result = new MixedProjectInspector(settings).parse(inputConfiguration);
        Runtime runtime = result.getRuntime();

        // A placeholder is EMPTY to every consumer downstream, so a hole in a Kotlin body reads as a body with
        // nothing to say. The editor must be told how many there are, and a run that has any is not certifiable.
        PlaceholderCensus census = PlaceholderCensus.of(result.getKotlinTypes());
        if (census.getTotal() > 0) {
            LOGGER.warn("{}", census.report(8));
            initProblems.add(census.report(8));
        }

        SourceSet sourceSetOfRequest = result.getKotlinBySourceSet().keySet().stream().findFirst()
                .orElseGet(() -> inputConfiguration.sourceSets().stream().findAny().orElse(null));
        emit(status, requestId, "hints", "loading analysis hints", null, null);
        int hints = hintsLoader.loadHints(runtime, sourceSetOfRequest);
        LOGGER.info("preloaded {} primary types of analysis hints", hints);

        Summary summary = result.getJavaSummary();
        boolean partialParse = summary != null && summary.haveErrors();
        if (partialParse) {
            LOGGER.info("Java parse produced {} error(s); analysing the type(s) that did parse",
                    summary.parseExceptions().size());
        }
        List<TypeInfo> primaryTypes = Stream.concat(result.getKotlinTypes().stream(), result.getJavaTypes().stream())
                .map(TypeInfo::primaryType)
                .distinct()
                .collect(Collectors.toList());
        LOGGER.info("mixed parse: {} Kotlin and {} Java type(s), {} primary", result.getKotlinTypes().size(),
                result.getJavaTypes().size(), primaryTypes.size());
        return new Parsed(result.getJavaInspector(), runtime, sourceSetOfRequest, primaryTypes, List.of(), summary,
                partialParse || census.getTotal() > 0, hints);
    }

    /**
     * Install the Kotlin front end in this JVM, once. @return null when it is (or already was) installed, else why
     * not — the reason reaches the editor as an init problem.
     */
    /**
     * Said to the IDE's user, who sets the front end in the IDE and not with the {@code -D} properties K2Realm's own
     * message names: a directory the IDE passed that holds no front end, or none passed at all (maddi-dist#6).
     */
    static String kotlinFrontEndProblem(String homeFromIde, String detail) {
        if (homeFromIde != null && !homeFromIde.isBlank()) {
            return "the Kotlin front end directory set in the IDE, " + homeFromIde + ", holds no usable front end ("
                   + detail + "). It must be a directory of the K2 jars, including maddi-kotlin-k2-<version>.jar"
                   + " (a maddi-kotlin distribution's lib-k2): check the Kotlin front end setting (IntelliJ: Settings"
                   + " → Tools → maddi → Kotlin front end override).";
        }
        return "the IDE started this daemon without a Kotlin front end. Accept the IDE's offer to download it, or set"
               + " a directory of the K2 jars (IntelliJ: Settings → Tools → maddi → Kotlin front end override), then"
               + " analyze again.";
    }

    private static String installKotlinFrontEnd() {
        try {
            K2Realm.installIfAbsent();
            return null;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Kotlin front end not available: {}", e.toString());
            return kotlinFrontEndProblem(System.getenv(DaemonMain.ENV_K2_HOME), e.getMessage());
        }
    }

    /**
     * Run the (blocking, possibly long) analysis on the CURRENT thread — maddi's runtime type-cache is not
     * thread-safe, so all pipeline work must stay on one thread. A separate heartbeat thread only sends status
     * frames (socket I/O, never touches maddi state) so the client's read doesn't time out on a large project.
     */
    private static <T> T runWithHeartbeat(StatusSink status, StreamingValueFeed progress,
                                          java.util.function.Supplier<T> analysis) {
        Thread heartbeat = new Thread(() -> {
            HeapSampler heap = new HeapSampler();
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(2_000);
                    status.status(progress.heartbeat(heap.sample()));
                }
            } catch (InterruptedException ignored) {
                // stopped
            }
        }, "maddi-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
        try {
            return analysis.get();
        } finally {
            heartbeat.interrupt();
        }
    }

    private static void emit(StatusSink status, String requestId, String phase, String message,
                             Integer typesDone, Integer typesTotal) {
        if (status != null) {
            status.status(new DaemonProtocol.Status(requestId, phase, message, typesDone, typesTotal));
        }
    }
}
