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

import com.fasterxml.jackson.core.JsonProcessingException;
import io.codelaser.maddi.ide.client.AnalysisModel;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools, as MCP declares them: a name, a description an agent reads to choose, an input schema, and a handler
 * that turns the arguments into a {@link MaddiService} call and its answer into JSON text.
 */
public final class McpTools {

    /** A tool's work: arguments in, an answer to serialize out. */
    @FunctionalInterface
    private interface Handler {
        Object handle(Args args, McpSyncServerExchange exchange, Object progressToken) throws Exception;
    }

    private final MaddiService service;
    private final McpJsonMapper jsonMapper;

    public McpTools(MaddiService service, McpJsonMapper jsonMapper) {
        this.service = service;
        this.jsonMapper = jsonMapper;
    }

    public static final String INSTRUCTIONS = """
            maddi analyses Java (and Kotlin) code for modification and immutability: which methods modify their \
            object or parameters, which fields are modified, which types are immutable, and contract violations. \
            Workflow: configure_project once, analyze (returns a run id at once; a large project takes minutes), \
            run_status with waitSeconds until FINISHED, then verdicts / explain / findings. Every answer carries a \
            certainty: only FINAL values are certified; PROVISIONAL and BEST_AVAILABLE values hold but may be \
            weaker than the code allows, so never present them as final. Analyse compiled projects: sources \
            resolve against class files.""";

    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        List<McpServerFeatures.SyncToolSpecification> out = new ArrayList<>();
        out.add(tool("configure_project", """
                        Configure a project to analyse. Either give inputConfiguration, the file written by the maddi \
                        Gradle plugin's writeInputConfiguration task (build/inputConfiguration.json) or the Maven \
                        plugin's write-input-configuration goal: it carries every source set with its own class path. \
                        Or name the directories yourself (any build): workingDirectory, sourceDirectories, classPath \
                        (jars and class directories), optional test directories. Compile the project first.""",
                """
                        {"type":"object","properties":{
                          "project":{"type":"string","description":"a name for later calls; default 'default'"},
                          "inputConfiguration":{"type":"string","description":"path of an inputConfiguration.json"},
                          "workingDirectory":{"type":"string","description":"project root; relative paths resolve against it"},
                          "sourceDirectories":{"type":"array","items":{"type":"string"}},
                          "testSourceDirectories":{"type":"array","items":{"type":"string"}},
                          "classPath":{"type":"array","items":{"type":"string"},"description":"jars and class directories of the main sources"},
                          "testClassPath":{"type":"array","items":{"type":"string"},"description":"in addition to classPath, for the tests"},
                          "outputDirectory":{"type":"string","description":"the main sources' compiled classes"},
                          "sourceRelease":{"type":"integer","description":"--release of the sources; default: the JDK's"},
                          "sdkHome":{"type":"string","description":"JDK the project compiles against; default: the daemon's"},
                          "jdkModules":{"type":"array","items":{"type":"string"},"description":"default: every java.* module of the JDK"},
                          "sourceEncoding":{"type":"string","description":"default UTF-8"}
                        }}""", this::configure));
        out.add(tool("list_projects", "The configured projects, their source sets and their last run.",
                "{\"type\":\"object\",\"properties\":{}}", (a, x, t) -> Map.of("projects", service.projects())));
        out.add(tool("analyze", """
                        Start an analysis of a configured project, or of part of it, and return its run id at once: \
                        a large project takes minutes. One run at a time. Follow it with run_status. Verdicts can be \
                        asked during the run (PROVISIONAL). sourceSets keeps only the named source sets (the others \
                        are then read from their compiled classes); packages restricts which sources are analysed \
                        ('a.b' one package, 'a.b.' a package and its subpackages, 'a.b.C' one type).""",
                """
                        {"type":"object","properties":{
                          "project":{"type":"string","description":"default 'default'"},
                          "sourceSets":{"type":"array","items":{"type":"string"}},
                          "packages":{"type":"array","items":{"type":"string"}},
                          "waitSeconds":{"type":"integer","description":"wait this long for the run to finish before answering; default 0"}
                        }}""", this::analyze));
        out.add(tool("run_status", """
                        The state of a run (RUNNING, FINISHED, FAILED, STOPPED), its progress, certainty and a summary \
                        of what it found. waitSeconds blocks until the run ends or the time is up, with progress \
                        notifications meanwhile.""",
                """
                        {"type":"object","properties":{
                          "runId":{"type":"string","description":"default: the latest run"},
                          "waitSeconds":{"type":"integer","description":"default 0, at most 600"}
                        }}""", this::status));
        out.add(tool("stop_run", "Stop a run. The values it streamed so far stay queryable, as PROVISIONAL.",
                """
                        {"type":"object","properties":{"runId":{"type":"string","description":"default: the latest run"}}}""",
                (a, x, t) -> service.stop(a.string("runId")).describe()));
        out.add(tool("verdicts", """
                        The analyzer's annotations on elements: @Modified/@NotModified methods, fields and parameters, \
                        @Immutable/@FinalFields/@Container types, @Independent, @NotNull, ... Select by element (a \
                        fully qualified name: a type selects its members too, and 'a.b.' a whole package; methods \
                        read 'a.b.C.m(String)', parameters 'a.b.C.m(String):0:s'), kind, file \
                        or annotation text.""",
                """
                        {"type":"object","properties":{
                          "project":{"type":"string"},
                          "element":{"type":"string"},
                          "kind":{"type":"string","enum":["TYPE","METHOD","FIELD","PARAMETER"]},
                          "file":{"type":"string","description":"suffix of the source path, e.g. a/b/C.java"},
                          "annotation":{"type":"string","description":"text an annotation must contain, e.g. @Immutable"},
                          "limit":{"type":"integer","description":"default 100"},
                          "offset":{"type":"integer"}
                        }}""", this::verdicts));
        out.add(tool("explain", """
                        One element in full: annotations with polarity, the analyzer's properties behind them (read \
                        these to see why a type is not @Immutable or a method is @Modified), the statements recorded \
                        as culprits, and the findings inside it. Unknown or ambiguous names answer candidates.""",
                """
                        {"type":"object","properties":{
                          "project":{"type":"string"},
                          "element":{"type":"string","description":"fully qualified name"}
                        },"required":["element"]}""", this::explain));
        out.add(tool("findings", """
                        Errors and warnings with their why-chains: contract violations, near misses (advisory: one \
                        member away from a stronger verdict), parse problems. Select by category prefix, severity \
                        (ERROR, WARN), file or element.""",
                """
                        {"type":"object","properties":{
                          "project":{"type":"string"},
                          "category":{"type":"string"},
                          "severity":{"type":"string","enum":["ERROR","WARN"]},
                          "file":{"type":"string"},
                          "element":{"type":"string"},
                          "limit":{"type":"integer","description":"default 100"},
                          "offset":{"type":"integer"}
                        }}""", this::findings));
        out.add(tool("library_annotations", """
                        What the last analysis knows of library types (JDK, jars): their methods' and parameters' \
                        verdicts, and where they come from (annotated hints, defaults, computed). Needs a finished run.""",
                """
                        {"type":"object","properties":{
                          "types":{"type":"array","items":{"type":"string"},"description":"fully qualified type names"}
                        },"required":["types"]}""",
                (a, x, t) -> service.libraryAnnotations(a.strings("types"))));
        return out;
    }

    private McpServerFeatures.SyncToolSpecification tool(String name, String description, String schema,
                                                         Handler handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder(name, jsonMapper, schema).description(description).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            Object token = request.meta() == null ? null : request.meta().get("progressToken");
            try {
                Object answer = handler.handle(new Args(request.arguments()), exchange, token);
                return McpSchema.CallToolResult.builder().addTextContent(json(answer)).isError(false).build();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error("interrupted");
            } catch (Exception e) {
                return error(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }).build();
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private String json(Object answer) throws JsonProcessingException {
        return service.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(answer);
    }

    // ---- handlers ----

    private Object configure(Args a, McpSyncServerExchange x, Object token) throws Exception {
        String project = a.string("project", "default");
        String inputConfiguration = a.string("inputConfiguration");
        AnalysisModel.AnalyzeConfig config;
        if (inputConfiguration != null) {
            config = ProjectConfigs.fromInputConfiguration(Path.of(inputConfiguration), service.mapper());
        } else {
            String workingDirectory = a.string("workingDirectory");
            if (workingDirectory == null) {
                throw new IllegalArgumentException("give inputConfiguration, or workingDirectory and sourceDirectories");
            }
            config = ProjectConfigs.fromDirectories(Path.of(workingDirectory).toAbsolutePath(),
                    a.strings("sourceDirectories"), a.strings("testSourceDirectories"), a.strings("classPath"),
                    a.strings("testClassPath"), a.string("outputDirectory"), a.integer("sourceRelease", 0),
                    a.string("sdkHome"), a.strings("jdkModules"), a.string("sourceEncoding"));
        }
        return service.configure(project, config);
    }

    private Object analyze(Args a, McpSyncServerExchange x, Object token) throws Exception {
        AnalysisRun run = service.analyze(a.string("project", "default"), a.strings("sourceSets"),
                a.strings("packages"));
        int wait = a.integer("waitSeconds", 0);
        if (wait > 0) service.await(run.id(), Math.min(wait, 600), r -> progress(x, token, r));
        return run.describe();
    }

    private Object status(Args a, McpSyncServerExchange x, Object token) throws Exception {
        int wait = Math.min(a.integer("waitSeconds", 0), 600);
        return service.await(a.string("runId"), wait, r -> progress(x, token, r)).describe();
    }

    private static void progress(McpSyncServerExchange exchange, Object token, AnalysisRun run) {
        if (token == null) return;
        double[] p = run.progress();
        try {
            exchange.progressNotification(McpSchema.ProgressNotification.builder(token, p == null ? 0 : p[0])
                    .total(p == null ? null : p[1]).message(run.progressMessage()).build());
        } catch (RuntimeException e) {
            // a client that went away: the run itself goes on
        }
    }

    private Object verdicts(Args a, McpSyncServerExchange x, Object token) {
        AnalysisRun run = service.resultsOf(a.string("project"));
        AnalysisModel.Result result = requireResult(run);
        List<AnalysisModel.ElementAnnotation> selected = Verdicts.select(result, a.string("element"),
                a.string("kind"), a.string("file"), a.string("annotation"));
        Map<String, Object> out = header(run);
        page(out, "elements", selected.stream().map(Verdicts::brief).toList(), a);
        return out;
    }

    private Object explain(Args a, McpSyncServerExchange x, Object token) {
        AnalysisRun run = service.resultsOf(a.string("project"));
        AnalysisModel.Result result = requireResult(run);
        String element = a.string("element");
        if (element == null) throw new IllegalArgumentException("element is required");
        Map<String, Object> out = header(run);
        List<AnalysisModel.ElementAnnotation> exact = result.elementAnnotations().stream()
                .filter(e -> element.equals(e.fqn())).toList();
        if (exact.size() == 1) {
            out.put("element", Verdicts.explain(result, exact.getFirst()));
            return out;
        }
        List<AnalysisModel.ElementAnnotation> candidates = exact.isEmpty()
                ? result.elementAnnotations().stream().filter(e -> e.fqn() != null && e.fqn().contains(element))
                .limit(30).toList()
                : exact;
        out.put("message", exact.isEmpty() ? "no element named " + element + "; candidates containing that text"
                : "several elements of that name");
        out.put("candidates", candidates.stream().map(Verdicts::brief).toList());
        return out;
    }

    private Object findings(Args a, McpSyncServerExchange x, Object token) {
        AnalysisRun run = service.resultsOf(a.string("project"));
        AnalysisModel.Result result = requireResult(run);
        List<AnalysisModel.Finding> selected = Verdicts.findings(result, a.string("category"), a.string("severity"),
                a.string("file"), a.string("element"));
        Map<String, Object> out = header(run);
        if (run.state() == AnalysisRun.State.RUNNING || run.state() == AnalysisRun.State.STOPPED) {
            out.put("note", "findings are computed after the fixpoint: a run that did not finish has none");
        }
        page(out, "findings", selected.stream().map(Verdicts::finding).toList(), a);
        return out;
    }

    private static AnalysisModel.Result requireResult(AnalysisRun run) {
        AnalysisModel.Result result = run.result();
        if (result == null) {
            throw new IllegalStateException("run " + run.id() + " (" + run.state() + ") has no values yet");
        }
        return result;
    }

    private static Map<String, Object> header(AnalysisRun run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.id());
        out.put("state", run.state().name());
        out.putAll(Verdicts.certaintyOf(run.certainty(), run.state() == AnalysisRun.State.STOPPED));
        return out;
    }

    private static void page(Map<String, Object> out, String key, List<?> all, Args a) {
        int offset = Math.max(0, a.integer("offset", 0));
        int limit = Math.max(1, a.integer("limit", 100));
        out.put("total", all.size());
        if (offset > 0) out.put("offset", offset);
        out.put(key, all.subList(Math.min(offset, all.size()), Math.min(offset + limit, all.size())));
        if (offset + limit < all.size()) out.put("more", "pass offset " + (offset + limit) + " for the next page");
    }

    /** The arguments of a call, as JSON decoded them. */
    private record Args(Map<String, Object> map) {
        Args {
            map = map == null ? Map.of() : map;
        }

        String string(String key) {
            Object value = map.get(key);
            return value == null || value.toString().isBlank() ? null : value.toString();
        }

        String string(String key, String orElse) {
            String value = string(key);
            return value == null ? orElse : value;
        }

        int integer(String key, int orElse) {
            Object value = map.get(key);
            if (value instanceof Number n) return n.intValue();
            if (value instanceof String s && !s.isBlank()) return Integer.parseInt(s.strip());
            return orElse;
        }

        List<String> strings(String key) {
            Object value = map.get(key);
            if (value == null) return List.of();
            if (value instanceof List<?> list) return list.stream().map(Object::toString).toList();
            return List.of(value.toString());
        }
    }
}
