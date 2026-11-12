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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.codelaser.maddi.analysis.api.AnalysisEngine;
import io.codelaser.maddi.analysis.api.AnalysisEngines;
import io.codelaser.maddi.analysis.api.AnalysisProblem;
import io.codelaser.maddi.analysis.api.ModificationOptions;
import io.codelaser.maddi.analysis.api.ModificationOutcome;
import io.codelaser.maddi.analysis.api.ModificationRequest;
import io.codelaser.maddi.analysis.api.NullabilityTarget;
import io.codelaser.maddi.analysis.api.PrepOutcome;
import io.codelaser.maddi.analysis.api.PrepRequest;
import io.codelaser.maddi.callgraph.CallGraphContributions;
import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.callgraph.ComputeCallGraph;
import io.codelaser.maddi.callgraph.PrimaryTypeUseGraph;
import io.codelaser.maddi.cst.api.analysis.Message;
import io.codelaser.maddi.cst.api.element.ModuleInfo;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.impl.analysis.NullabilityFacts;
import io.codelaser.maddi.cst.print.kotlin.KotlinPrintOptions;
import io.codelaser.maddi.cst.print.kotlin.KotlinTranslator;
import io.codelaser.maddi.cst.print.kotlin.KotlinWorld;
import io.codelaser.maddi.graph.G;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.api.parser.ParseResult;
import io.codelaser.maddi.inspection.api.parser.Summary;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.inspection.mixed.MixedProjectInspector;
import io.codelaser.maddi.inspection.openjdk.JavaInspectorImpl;
import io.codelaser.maddi.inspection.resource.DetectKotlinSources;
import io.codelaser.maddi.kotlin.api.PlaceholderCensus;
import io.codelaser.maddi.kotlin.realm.K2Realm;

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
 * <p>
 * With {@code -Dmaddi.daemon.warm=true} (experimental, off by default) the inspector and the run's model stay for the
 * next request instead (docs/design/incremental-and-overlay.md §3). A request with the same configuration then asks
 * the inspector which source files changed: none, and the previous result is returned at once; some, and they are
 * re-parsed, their dependents rewired, prep and the call graph redone for those types only, and the analysis seeded
 * with them. Such a result is {@link DaemonProtocol#OUTCOME_INCREMENTAL}: a seeded analysis does not run MODREACH and
 * the eventual pass, so it is best available, never certified. A cold run is the fallback for anything else: the
 * first request, another configuration, Kotlin sources, a partial parse, a file added or removed, any exception.
 * <p>
 * With {@code -Dmaddi.daemon.persist} (a directory, or {@code true} for {@code ~/.maddi/daemon-results}) every result
 * is stored, and a request whose inputs did not change since a stored result (the engine, the JDK, the class path, the
 * sources: {@link ResultStore}) is answered with it instead of a cold run, its outcome as it was. That holds across a
 * restart of the daemon and across a switch to another project and back. Only the result is stored, not the model:
 * the next edit after a result read back is analysed cold, and a request that needs the model builds it first.
 * {@code cold} on the request skips the store.
 */
public class WarmAnalysisService implements AnalyzeHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(WarmAnalysisService.class);

    /** Keep the model between requests and re-analyse incrementally; see the class comment. */
    static final boolean WARM = Boolean.getBoolean("maddi.daemon.warm");

    /*
     The early cutoff of an incremental re-analysis (on unless -Dmaddi.daemon.cutoff=false). The rewired types carry
     their analysis values onto the new objects, the seed is the changed types alone, and the analysis compares what
     their dependents can see of them with what the previous run left (ModificationRequest.seedPrevious): equal, the
     carried values stand and the run ends there; otherwise it falls back to a cold analysis of the whole order. Off,
     the seed is the changed types and their dependents, cleared; every element the worklist reaches is cleared too,
     counts as changed, and wakes its neighbours, so an edit anywhere re-analyses the connected project.
     */
    static final boolean CUTOFF = !"false".equals(System.getProperty("maddi.daemon.cutoff"));

    private final InputConfigurationAssembler assembler = new InputConfigurationAssembler();
    private final boolean warm;
    /** The static-side-effect analysis for this service's runs; null keeps the analyzer's default. */
    private final Boolean staticSideEffects;
    /** -Dmaddi.daemon.persist: the results of earlier runs on disk; null when off. */
    private final ResultStore store;

    public WarmAnalysisService() {
        this(null, WARM, ResultStore.fromSystemProperty());
    }

    /** For tests: switch the static-side-effect analysis on or off for every run of this service. */
    WarmAnalysisService(Boolean staticSideEffects) {
        this(staticSideEffects, WARM, null);
    }

    /** For tests: also whether the model stays for an incremental re-analysis. */
    WarmAnalysisService(Boolean staticSideEffects, boolean warm) {
        this(staticSideEffects, warm, null);
    }

    /** For tests: also where earlier results are stored (-Dmaddi.daemon.persist); null: nowhere. */
    WarmAnalysisService(Boolean staticSideEffects, boolean warm, ResultStore store) {
        this.staticSideEffects = staticSideEffects;
        this.warm = warm;
        this.store = store;
        if (store != null) LOGGER.info("persist: results are stored in {}", store.directory());
    }

    /** What either parse path hands to the shared tail. */
    private record Parsed(JavaInspector inspector,
                          Runtime runtime,
                          SourceSet sourceSetOfRequest,
                          Collection<TypeInfo> primaryTypes,
                          Collection<ModuleInfo> moduleInfos,
                          Summary summary,
                          boolean partial,
                          int hints,
                          JavaInspector.ParseOptions parseOptions) { // null: the mixed parse, which cannot re-parse
    }

    /*
     -Dmaddi.daemon.warm: what an incremental re-analysis starts from, as the last run left it. The inspector, its
     parse and the analysis values on its objects; the call graph, and its per-type contributions for the next prep;
     the findings, each with the element it is about, since a seeded analysis only reports on what it analysed; and
     the last result, returned as it is when nothing changed. Dropped as any run starts, and only set again by a run
     that ends well.
     */
    private record WarmState(DaemonProtocol.AnalyzeConfig config,
                             InputConfiguration inputConfiguration,
                             JavaInspector inspector,
                             JavaInspector.ParseOptions parseOptions,
                             SourceSet sourceSet,
                             ParseResult parseResult,
                             Collection<ModuleInfo> moduleInfos,
                             G<Info> graph,
                             CallGraphContributions contributions,
                             List<KeptFinding> findings,
                             DaemonProtocol.Result result) {
    }

    /** A finding from the analysis, and the element it is about (null when it names none). */
    private record KeptFinding(Info info, DaemonProtocol.Finding finding) {
    }

    private WarmState warmState;

    /*
     The last run's model, kept for the library types the editor asks about (libraryAnnotations), and dropped as the
     next run starts, so that two models never live at once. Requests come one at a time, on the connection's thread,
     which keeps maddi's runtime on one thread as the analysis requires.
     */
    private record LastRun(Runtime runtime, SourceSet sourceSet, ResultCollector collector, AnalysisEngine engine,
                           List<Info> order, Collection<TypeInfo> primaryTypes) {
    }

    private LastRun lastRun;

    /*
     The request whose result was read back from the store (-Dmaddi.daemon.persist): there is no model behind it, and
     a request that needs one (libraryAnnotations, translate) builds it with a cold run of this request first.
     */
    private DaemonProtocol.AnalyzeProject restoredRequest;

    /* for tests: how many elements the last incremental run analysed; -1 when it fell back to the cold analysis */
    private int lastIncrementalScope;

    int lastIncrementalScope() {
        return lastIncrementalScope;
    }

    /* for tests: whether the last result was read back from the store */
    boolean lastResultRestored() {
        return restoredRequest != null;
    }

    /* the model of the last run; after a result read back from the store, a cold run of its request makes one */
    private LastRun lastRunOrRebuild() throws Exception {
        if (lastRun == null && restoredRequest != null) {
            DaemonProtocol.AnalyzeProject request = restoredRequest;
            LOGGER.info("persist: the stored result has no model; analysing again to build one");
            analyze(new DaemonProtocol.AnalyzeProject(request.requestId(), request.config(), true), status -> { });
        }
        return lastRun;
    }

    @Override
    public DaemonProtocol.LibraryResult libraryAnnotations(DaemonProtocol.LibraryAnnotations request) {
        LastRun run;
        try {
            run = lastRunOrRebuild();
        } catch (Exception e) {
            LOGGER.warn("persist: cannot build the model again", e);
            run = null;
        }
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

    /*
     As the CLI's --translate kotlin (RunAnalyzer): the nullability pass's facts, !! where a value the analysis cannot
     prove non-null meets a non-null type. The translator writes files; they are read back and the directory removed.
     */
    @Override
    public DaemonProtocol.TranslateResult translate(DaemonProtocol.Translate request) throws IOException {
        LastRun run;
        try {
            run = lastRunOrRebuild();
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        if (run == null) {
            return new DaemonProtocol.TranslateResult(request.requestId(), List.of(), List.of(), List.of(),
                    request.types());
        }
        Map<String, TypeInfo> byName = new HashMap<>();
        for (TypeInfo t : run.primaryTypes()) byName.put(t.fullyQualifiedName(), t);
        List<TypeInfo> types = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String fqn : request.types()) {
            TypeInfo t = byName.get(fqn);
            if (t == null) unknown.add(fqn);
            else types.add(t);
        }
        NullabilityFacts facts = run.engine().nullabilityFacts(run.order(), NullabilityTarget.KOTLIN);
        KotlinPrintOptions options = new KotlinPrintOptions(facts, KotlinPrintOptions.NullCheck.ASSERT)
                .withWorld(request.together() ? KotlinWorld.of(types) : KotlinWorld.FILE);
        Path out = Files.createTempDirectory("maddi-translate");
        try {
            KotlinTranslator.Result result = KotlinTranslator.translate(run.runtime(), types, options, out);
            Map<Path, String> typeOfFile = new HashMap<>();
            for (TypeInfo t : types) {
                typeOfFile.put(out.resolve(t.packageName().replace('.', '/')).resolve(t.simpleName() + ".kt")
                        .toAbsolutePath().normalize(), t.fullyQualifiedName());
            }
            List<DaemonProtocol.TranslatedFile> files = new ArrayList<>();
            for (Path file : result.files()) {
                files.add(new DaemonProtocol.TranslatedFile(typeOfFile.get(file),
                        out.toAbsolutePath().normalize().relativize(file).toString().replace('\\', '/'),
                        Files.readString(file)));
            }
            List<DaemonProtocol.TranslationMessage> messages = result.messages().stream()
                    .map(m -> new DaemonProtocol.TranslationMessage(m.severity().name(), m.code().name(), m.type(),
                            m.line(), m.position(), m.detail()))
                    .toList();
            return new DaemonProtocol.TranslateResult(request.requestId(), files, messages, result.crashes(),
                    unknown);
        } finally {
            try (Stream<Path> walk = Files.walk(out)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Override
    public DaemonProtocol.Result analyze(DaemonProtocol.AnalyzeProject request, StatusSink status) throws Exception {
        long start = System.currentTimeMillis();
        // -Dmaddi.daemon.persist: the request's key, taken before anything reads a source (see ResultStore)
        String configuration = null;
        ResultStore.Key key = null;
        if (store != null) {
            try {
                configuration = store.configuration(request.config(), "staticSideEffects=" + staticSideEffects);
                key = store.key(request.config());
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("persist: cannot compute the key of this request, not using the store: {}", e.toString());
                key = null;
            }
        }
        if (warm && warmState != null && request.cold()) {
            LOGGER.info("incremental: the client asked for a cold run, analysing from scratch");
        } else if (warm && warmState != null) {
            WarmState state = warmState;
            warmState = null; // whatever happens below, a failed attempt leaves nothing behind
            try {
                DaemonProtocol.Result result = analyzeIncrementally(state, request, status, start);
                if (result != null) {
                    // nothing changed: the previous result, returned as it is, was stored already
                    boolean same = result.elementAnnotations() == state.result().elementAnnotations();
                    if (key != null && !same) store.save(configuration, key, result);
                    return result;
                }
            } catch (Exception | AssertionError e) {
                LOGGER.warn("incremental re-analysis failed, analysing from scratch", e);
            }
        }
        warmState = null;
        lastRun = null;
        restoredRequest = null;
        String requestId = request.requestId();
        if (key != null && !request.cold()) {
            emit(status, requestId, "initialize", "looking for a stored result", null, null);
            DaemonProtocol.Result stored = store.lookup(configuration, key);
            if (stored != null) {
                long elapsed = System.currentTimeMillis() - start;
                LOGGER.info("persist: the inputs did not change, the stored result stands ({} ms, {})", elapsed,
                        stored.outcome());
                restoredRequest = request;
                return new DaemonProtocol.Result(requestId, stored.findings(), stored.elementAnnotations(),
                        stored.initializationProblems(), stored.parseErrorCount(), stored.hintsLoaded(), elapsed,
                        stored.outcome());
            }
        }
        boolean parallel = request.config().parallel();

        emit(status, requestId, "initialize", "building inspector", null, null);
        InputConfiguration inputConfiguration = assembler.build(request.config());
        if (inputConfiguration.sourceSets().isEmpty()) {
            // said here, in words: further down, an empty configuration ends in an NPE while loading the hints
            throw new IllegalArgumentException("the IDE sent no source sets for " + request.config().workingDirectory()
                                               + ": it found no source root in any module");
        }
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
        ResultCollector collector = new ResultCollector(engine, parsed.runtime(), parsed.sourceSetOfRequest(),
                request.config().containerWhyMaxSlotsOrDefault());

        emit(status, requestId, "prep", "call graph", null, parsed.primaryTypes().size());
        // Fault-tolerant like RunAnalyzer's: on a partial parse a type whose supertype did not resolve is far
        // likelier to trip prep, and one such type must not abort the run for everything else. The isolated
        // failures are reported through the prep outcome's problems below.
        // warm: the call graph keeps each type's contribution, so that the next prep walks the re-parsed types only
        boolean keep = warm && parsed.parseOptions() != null && !parsed.partial();
        CallGraphContributions contributions = keep ? new CallGraphContributions() : null;
        PrepOutcome prep = engine.prep(new PrepRequest(parsed.runtime(), Set.copyOf(parsed.primaryTypes()),
                parsed.moduleInfos(), typeInfo -> false, parallel, true, contributions));
        ComputeCallGraph ccg = prep.callGraph();

        emit(status, requestId, "order", "analysis order", null, parsed.primaryTypes().size());
        List<Info> order = new ComputeAnalysisOrder().go(ccg.graph(), parallel);

        emit(status, requestId, "analyze", "modification analysis", 0, order.size());
        ModificationOptions modOptions = modificationOptions(request.config());
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
        List<KeptFinding> analysisFindings = keptFindings(collector, messages);
        List<DaemonProtocol.Finding> findings = new ArrayList<>(analysisFindings.stream().map(KeptFinding::finding)
                .toList());
        findings.addAll(collector.parseFindings(summary));
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
        lastRun = new LastRun(parsed.runtime(), parsed.sourceSetOfRequest(), collector, engine, order,
                parsed.primaryTypes());
        DaemonProtocol.Result result = new DaemonProtocol.Result(requestId, findings, elementAnnotations, initProblems,
                parseErrors, parsed.hints(), elapsed, outcome);
        if (keep) {
            warmState = new WarmState(request.config(), inputConfiguration, parsed.inspector(), parsed.parseOptions(),
                    parsed.sourceSetOfRequest(), summary.parseResult(), parsed.moduleInfos(), ccg.graph(),
                    contributions, analysisFindings, result);
        }
        if (key != null) store.save(configuration, key, result);
        return result;
    }

    /*
     -Dmaddi.daemon.warm: the re-analysis after an edit, on the state the previous run left; null when it must be a
     cold run instead. On the parse, prep and call graph, this is RunRewireTests' re-parse (maddi-run-rewire); its oracle
     is what tests that a warm parse and prep equal a cold one.
     */
    private DaemonProtocol.Result analyzeIncrementally(WarmState state, DaemonProtocol.AnalyzeProject request,
                                                       StatusSink status, long start) throws IOException {
        String requestId = request.requestId();
        if (!state.config().equals(request.config())) {
            LOGGER.info("incremental: the configuration changed, analysing from scratch");
            return null;
        }
        JavaInspector inspector = state.inspector();
        emit(status, requestId, "reload", "looking for changed sources", null, null);
        Set<URI> filesBefore = sourceFileUris(inspector);
        JavaInspector.ReloadResult reload = inspector.reloadSources(state.inputConfiguration(), Map.of());
        Set<URI> filesAfter = sourceFileUris(inspector);
        if (!filesAfter.equals(filesBefore)) {
            // the reload names changed types only; a new file has none yet, a removed one leaves its types behind
            LOGGER.info("incremental: source files were added or removed, analysing from scratch");
            return null;
        }
        Set<TypeInfo> changed = reload.sourceHasChanged();
        if (changed.isEmpty()) {
            long elapsed = System.currentTimeMillis() - start;
            LOGGER.info("incremental: no source changed, the previous result stands ({} ms)", elapsed);
            DaemonProtocol.Result previous = state.result();
            warmState = state;
            return new DaemonProtocol.Result(requestId, previous.findings(), previous.elementAnnotations(),
                    previous.initializationProblems(), previous.parseErrorCount(), previous.hintsLoaded(), elapsed,
                    previous.outcome());
        }

        // the changed types are re-parsed, the types that depend on them rewired, the others kept as they are
        Set<TypeInfo> dependents = new PrimaryTypeUseGraph(state.graph()).dependentsOf(changed);
        JavaInspector.Invalidated invalidated = t -> changed.contains(t) ? JavaInspector.InvalidationState.INVALID
                : dependents.contains(t) ? JavaInspector.InvalidationState.REWIRE
                : JavaInspector.InvalidationState.UNCHANGED;
        LOGGER.info("incremental: {} type(s) changed, {} dependent type(s) to rewire", changed.size(),
                dependents.size());
        emit(status, requestId, "parse", "re-parsing " + changed.size() + " changed type(s)", null, null);
        JavaInspector.ParseOptions base = state.parseOptions();
        Summary summary = inspector.parse(new JavaInspector.ParseOptions(base.failFast(), base.detailedSources(),
                invalidated, base.parallel(), base.lombok(), base.ignoreModule(), base.parameterNames(),
                base.syntheticListField()));
        if (summary.haveErrors()) {
            LOGGER.info("incremental: the re-parse has {} error(s), analysing from scratch",
                    summary.parseExceptions().size());
            return null;
        }
        ParseResult parseResult = summary.parseResult();
        Runtime runtime = inspector.runtime();
        boolean parallel = request.config().parallel();

        AnalysisEngine engine = AnalysisEngines.require("the IDE daemon's modification analysis");
        emit(status, requestId, "prep", "call graph", null, parseResult.primaryTypes().size());
        // every type that arrived prepped is skipped, and the call graph walks only the types it holds no part of
        PrepOutcome prep = engine.prep(new PrepRequest(runtime, Set.copyOf(parseResult.primaryTypes()),
                state.moduleInfos(), typeInfo -> false, parallel, true, state.contributions()));
        G<Info> graph = prep.callGraph().graph();
        List<Info> order = new ComputeAnalysisOrder().go(graph, parallel);

        // the seed: every element of the re-parsed types, as their new objects. With the cutoff (CUTOFF) the rewired
        // types carry the previous run's analysis values; without it they carry none
        // (docs/design/incremental-and-overlay.md) and are seeded too, so they are analysed again
        Set<TypeInfo> seedTypes = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> seedNames = new HashSet<>();
        for (TypeInfo t : changed) seedNames.add(t.fullyQualifiedName());
        Set<TypeInfo> carryFrom = null;
        Set<TypeInfo> seedPrevious = null;
        if (CUTOFF) {
            // every previous primary type but the changed ones: the rewired ones, and whatever else the re-parse
            // rebuilt; a kept type maps onto itself and is skipped (RunRewireTests does the same)
            Set<TypeInfo> previous = new HashSet<>(state.parseResult().primaryTypes());
            previous.removeAll(changed);
            carryFrom = Set.copyOf(previous);
            seedPrevious = Set.copyOf(changed);
        } else {
            for (TypeInfo t : dependents) seedNames.add(t.fullyQualifiedName());
        }
        for (String name : seedNames) {
            TypeInfo t = parseResult.findType(name);
            if (t != null) {
                seedTypes.add(t);
            } else if (changed.stream().anyMatch(c -> c.fullyQualifiedName().equals(name))) {
                // a changed type the re-parse no longer has by that name: renamed, or moved to another package. Its
                // new type would be in neither the seed nor the carry, and stay unanalysed.
                LOGGER.info("incremental: changed type {} is gone from the re-parse, analysing from scratch", name);
                return null;
            }
        }
        Set<Info> seed = new HashSet<>();
        for (Info info : order) {
            if (info instanceof ModuleInfo) continue; // asking a module descriptor for its type throws
            TypeInfo type = info instanceof TypeInfo ti ? ti : info.typeInfo();
            if (type != null && seedTypes.contains(type.primaryType())) seed.add(info);
        }

        ResultCollector collector = new ResultCollector(engine, runtime, state.sourceSet(),
                request.config().containerWhyMaxSlotsOrDefault());
        emit(status, requestId, "analyze", "modification analysis, seeded with " + seedTypes.size() + " type(s)",
                0, order.size());
        StreamingValueFeed valueFeed = new StreamingValueFeed(status, requestId, collector);
        ModificationRequest modRequest = new ModificationRequest(inspector, order, graph, parseResult.primaryTypes(),
                modificationOptions(request.config()), valueFeed, seed, carryFrom, seedPrevious);
        ModificationOutcome outcome = runWithHeartbeat(status, valueFeed, () -> {
            try {
                return engine.modification(modRequest);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        emit(status, requestId, "collect", "collecting results", null, null);
        // The passes spoke about their scope only, the contract check after them (contracts, near misses) about the
        // whole order again. So an earlier finding stands unless it is about a re-parsed or rewired type (those are new
        // objects now) or an element the passes went over again, or the contract check gave it once more.
        // No scope: the early cutoff fell back to a cold analysis of the whole order, which keeps no earlier finding.
        boolean fellBackCold = outcome.scope() == null;
        Set<Info> scope = fellBackCold ? Set.of() : outcome.scope();
        if (fellBackCold) LOGGER.info("incremental: the early cutoff fell back to the cold analysis");
        lastIncrementalScope = fellBackCold ? -1 : scope.size();
        List<KeptFinding> fresh = keptFindings(collector, outcome.messages());
        Set<DaemonProtocol.Finding> freshFindings = new HashSet<>(fresh.stream().map(KeptFinding::finding).toList());
        List<KeptFinding> analysisFindings = new ArrayList<>();
        for (KeptFinding kept : fellBackCold ? List.<KeptFinding>of() : state.findings()) {
            if (kept.info() == null || scope.contains(kept.info()) || freshFindings.contains(kept.finding())) continue;
            TypeInfo type = kept.info() instanceof TypeInfo ti ? ti : kept.info().typeInfo();
            if (type == null || seedNames.contains(type.primaryType().fullyQualifiedName())) continue;
            analysisFindings.add(kept);
        }
        analysisFindings.addAll(fresh);
        List<DaemonProtocol.Finding> findings = new ArrayList<>(analysisFindings.stream().map(KeptFinding::finding)
                .toList());
        findings.addAll(collector.parseFindings(summary));
        List<DaemonProtocol.ElementAnnotation> elementAnnotations =
                collector.collectElementAnnotations(parseResult.primaryTypes());

        long elapsed = System.currentTimeMillis() - start;
        LOGGER.info("incremental analysis complete in {} ms: {} type(s) seeded, {} element(s) analysed, {} findings,"
                    + " {} element annotations", elapsed, seedTypes.size(), scope.size(), findings.size(),
                elementAnnotations.size());
        DaemonProtocol.Result previous = state.result();
        DaemonProtocol.Result result = new DaemonProtocol.Result(requestId, findings, elementAnnotations,
                previous.initializationProblems(), summary.parseExceptions().size(), previous.hintsLoaded(), elapsed,
                DaemonProtocol.OUTCOME_INCREMENTAL);
        lastRun = new LastRun(runtime, state.sourceSet(), collector, engine, order, parseResult.primaryTypes());
        warmState = new WarmState(state.config(), state.inputConfiguration(), inspector, base, state.sourceSet(),
                parseResult, state.moduleInfos(), graph, state.contributions(), analysisFindings, result);
        return result;
    }

    /* the project's source files: on disk, so not the in-memory preload unit the hints loader parses (mem:) */
    private static Set<URI> sourceFileUris(JavaInspector inspector) {
        Set<URI> uris = new HashSet<>();
        inspector.sourceFiles().forEach(sf -> {
            if ("file".equals(sf.uri().getScheme())) uris.add(sf.uri());
        });
        return uris;
    }

    /* the analysis' messages as findings, each with the element it is about */
    private static List<KeptFinding> keptFindings(ResultCollector collector, List<Message> messages) {
        List<KeptFinding> kept = new ArrayList<>();
        if (messages == null) return kept;
        for (Message m : messages) {
            for (DaemonProtocol.Finding f : collector.collectFindings(List.of(m), null)) {
                kept.add(new KeptFinding(m.info(), f));
            }
        }
        return kept;
    }

    private ModificationOptions modificationOptions(DaemonProtocol.AnalyzeConfig config) {
        return new ModificationOptions(
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
                config.warnNearMisses(),
                false,  // environment gates: the CLI's, not the daemon's
                false,  // store fingerprints
                staticSideEffects,
                null)   // eventual cluster: the analyzer's default
                // the nullability pass after the fixpoint, writing NULLABILITY_* for the @NotNull/@Nullable hints
                .withNullability(true)
                // beside a verdict, the first statement responsible for it, for the "why?" in the gutter tooltip
                .withCulprits(true);
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
                parseResult.sourceSetToModuleInfoMap().values(), summary, partialParse, hints, parseOptions);
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
                partialParse || census.getTotal() > 0, hints, null);
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
                                          Supplier<T> analysis) {
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
