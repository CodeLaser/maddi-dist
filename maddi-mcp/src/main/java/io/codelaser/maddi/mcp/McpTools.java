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
import io.codelaser.maddi.inspection.api.resource.BuildFiles;
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
            Workflow: configure_project once (projects and finished runs are stored, and survive a restart), \
            analyze (returns a run id at once; a large project takes minutes), run_status with waitSeconds until \
            FINISHED, then overview, verdicts / explain / element_at / findings, and after an edit and a new run, \
            diff_runs. A stored run says when it was analysed and how many sources changed since. Every answer carries a \
            certainty: only FINAL values are certified; PROVISIONAL and BEST_AVAILABLE values hold but may be \
            weaker than the code allows, so never present them as final. Analyse compiled projects: sources \
            resolve against class files.""";

    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        List<McpServerFeatures.SyncToolSpecification> out = new ArrayList<>();
        out.add(tool("configure_project", """
                        Configure a project to analyse; it is stored, and survives a restart of this server. Either \
                        give inputConfiguration, the file written by the maddi Gradle plugin's \
                        maddi-write-input-configuration task (build/inputConfiguration.json), the Maven plugin's \
                        maddi:write-input-configuration goal (target/inputConfiguration.json) or the CLI's \
                        --write-input-configuration: it carries every source set with its own class path, and the \
                        hashes of the build files that shaped it. Each analyze checks those hashes, and when one \
                        changed computes the configuration again: with the plugin's task or goal, or with the \
                        'regenerate' command given here (needed for a CLI-written file). Or name the directories \
                        yourself (any build): workingDirectory, sourceDirectories, classPath (jars and class \
                        directories), optional test directories; a change to its Gradle or Maven files is then noticed \
                        but cannot be redone. Compile the project first. Or, for a Gradle build without the maddi \
                        plugin: workingDirectory alone (or with build 'gradle'). The configuration is then made by \
                        compiling every Java source set with javac's arguments logged, at the next analyze (or \
                        analyze with configureOnly), and made again whenever the build files change.""",
                """
                        {"type":"object","properties":{
                          "project":{"type":"string","description":"a name for later calls; default 'default'"},
                          "daemonJdk":{"type":"string","description":"a JDK 25+ to run the daemon on for this project, when the server's does not suit it (an annotation processor such as Lombok that does not support it). Given with project alone: changes only that, keeping the configuration"},
                          "build":{"type":"string","enum":["gradle"],"description":"make the configuration from this build; default when only workingDirectory is given and it holds a Gradle build"},
                          "inputConfiguration":{"type":"string","description":"path of an inputConfiguration.json"},
                          "regenerate":{"type":"array","items":{"type":"string"},"description":"a command that writes the inputConfiguration file again; default: the plugin's task or goal"},
                          "regenerateDirectory":{"type":"string","description":"where to run it; default: the build's root"},
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
        out.add(tool("list_projects", """
                        The configured projects (stored: they survive a restart), their source sets, whether their \
                        build files still match the configuration (FRESH, STALE, UNCHECKED), how they would be \
                        computed again, and their last run.""",
                "{\"type\":\"object\",\"properties\":{}}", (a, x, t) -> Map.of("projects", service.projects())));
        out.add(tool("analyze", """
                        Start an analysis of a configured project, or of part of it, and return its run id at once: \
                        a large project takes minutes. One run at a time. Follow it with run_status. Verdicts can be \
                        asked during the run (PROVISIONAL). sourceSets keeps only the named source sets (the others \
                        are then read from their compiled classes); packages restricts which sources are analysed \
                        ('a.b' one package, 'a.b.' a package and its subpackages, 'a.b.C' one type).""",
                """
                        {"type":"object","properties":{
                          "project":{"type":"string","description":"default: the only project configured, else 'default'"},
                          "sourceSets":{"type":"array","items":{"type":"string"}},
                          "packages":{"type":"array","items":{"type":"string"}},
                          "waitSeconds":{"type":"integer","description":"wait this long for the run to finish before answering; default 0"},
                          "allowStale":{"type":"boolean","description":"analyse with a stale configuration that cannot be computed again, rather than fail"},
                          "configureOnly":{"type":"boolean","description":"bring the configuration up to date (make it, for a gradle project), and stop there"}
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
                (a, x, t) -> service.describe(service.stop(a.string("runId")))));
        out.add(tool("overview", """
                        The state of a project, a package ('a.b.') or a type in one answer: types grouped by \
                        immutability level (strongest first), methods that modify (and those that modify only the \
                        hidden content: the objects they hold, not their own structure), modified fields and \
                        parameters, findings by category, and per package when there are several. Start here.""",
                """
                        {"type":"object","properties":{
                          "project":{"type":"string"},
                          "element":{"type":"string","description":"a package ('a.b.'), a type, or none for all"},
                          "limit":{"type":"integer","description":"names listed per group; default 30"}
                        }}""", this::overview));
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
                        as culprits ('whys'; one with 'holds':false explains a verdict the element does NOT have: \
                        why a type is not @Container, one step per modified parameter), and the findings about it: located inside it, or through a cause inside it \
                        ('via':'cause': a contract on an interface broken by an implementation elsewhere), or naming \
                        it ('via':'named'). Unknown or ambiguous names answer candidates.""",
                """
                        {"type":"object","properties":{
                          "project":{"type":"string"},
                          "element":{"type":"string","description":"fully qualified name"}
                        },"required":["element"]}""", this::explain));
        out.add(tool("element_at", """
                        The element at a position in a source file, explained in full as by explain, with the \
                        elements around it. Without a column, a method's line gives the method, not its parameters.""",
                """
                        {"type":"object","properties":{
                          "project":{"type":"string"},
                          "file":{"type":"string","description":"the source path, or a suffix of it, e.g. a/b/C.java"},
                          "line":{"type":"integer","description":"1-based"},
                          "column":{"type":"integer","description":"1-based; optional"}
                        },"required":["file","line"]}""", this::elementAt));
        out.add(tool("diff_runs", """
                        What changed between two runs of a project: elements whose annotations changed (with what was \
                        added and removed), elements that appeared or disappeared, findings that are new or gone. \
                        Edit, analyze again, then diff_runs to see what the edit did. Elements are matched by name, \
                        not position. Default: the project's latest run against the one before it.""",
                """
                        {"type":"object","properties":{
                          "project":{"type":"string"},
                          "from":{"type":"string","description":"run id; default: the run before 'to'"},
                          "to":{"type":"string","description":"run id; default: the project's latest run"},
                          "element":{"type":"string","description":"limit to these elements, as in verdicts"},
                          "limit":{"type":"integer","description":"default 100 changed elements"},
                          "offset":{"type":"integer"}
                        }}""", this::diffRuns));
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
        String daemonJdk = a.string("daemonJdk");
        if (daemonJdk != null && inputConfiguration == null && a.string("workingDirectory") == null) {
            return service.setDaemonJdk(project, daemonJdk);
        }
        String now = java.time.Instant.now().toString();
        if (inputConfiguration != null) {
            Path file = Path.of(inputConfiguration).toAbsolutePath().normalize();
            AnalysisModel.AnalyzeConfig config = ProjectConfigs.fromInputConfiguration(file, service.mapper());
            BuildFiles buildFiles = ProjectConfigs.buildFilesOf(file, service.mapper());
            ProjectStore.Regenerate regenerate;
            List<String> command = a.strings("regenerate");
            if (!command.isEmpty()) {
                String dir = a.string("regenerateDirectory");
                regenerate = new ProjectStore.Regenerate(command, dir != null ? dir
                        : buildFiles != null && buildFiles.root() != null ? buildFiles.root() : config.workingDirectory());
            } else {
                regenerate = ProjectConfigs.defaultRegenerate(buildFiles, file);
            }
            return service.configure(new ProjectStore.StoredProject(project,
                    ProjectStore.StoredProject.INPUT_CONFIGURATION, file.toString(), regenerate, buildFiles, now,
                    config, daemonJdk));
        }
        String build = a.string("build");
        String workingDirectoryArg = a.string("workingDirectory");
        if (build == null && workingDirectoryArg != null && a.strings("sourceDirectories").isEmpty()
            && GradleCapture.isGradleBuild(Path.of(workingDirectoryArg))) {
            build = GradleCapture.BUILTIN;
        }
        if (build != null) {
            if (!GradleCapture.BUILTIN.equals(build)) throw new IllegalArgumentException("unknown build '" + build + "'");
            if (workingDirectoryArg == null) throw new IllegalArgumentException("a gradle project needs workingDirectory");
            Path root = Path.of(workingDirectoryArg).toAbsolutePath().normalize();
            if (!GradleCapture.isGradleBuild(root)) {
                throw new IllegalArgumentException(root + " holds no settings.gradle(.kts) or build.gradle(.kts)");
            }
            return service.configure(new ProjectStore.StoredProject(project, ProjectStore.StoredProject.GRADLE,
                    service.inputConfigurationOf(project).toString(),
                    ProjectStore.Regenerate.builtin(GradleCapture.BUILTIN, root.toString()), null, now, null,
                    daemonJdk));
        }
        AnalysisModel.AnalyzeConfig config;
        {
            String workingDirectory = a.string("workingDirectory");
            if (workingDirectory == null) {
                throw new IllegalArgumentException("give inputConfiguration, or workingDirectory and sourceDirectories");
            }
            config = ProjectConfigs.fromDirectories(Path.of(workingDirectory).toAbsolutePath(),
                    a.strings("sourceDirectories"), a.strings("testSourceDirectories"), a.strings("classPath"),
                    a.strings("testClassPath"), a.string("outputDirectory"), a.integer("sourceRelease", 0),
                    a.string("sdkHome"), a.strings("jdkModules"), a.string("sourceEncoding"));
        }
        return service.configure(new ProjectStore.StoredProject(project, ProjectStore.StoredProject.DIRECTORIES,
                null, null, BuildFileCheck.capture(config), now, config, daemonJdk));
    }

    private Object analyze(Args a, McpSyncServerExchange x, Object token) throws Exception {
        AnalysisRun run = service.analyze(a.string("project"), a.strings("sourceSets"),
                a.strings("packages"), a.bool("allowStale"), a.bool("configureOnly"));
        int wait = a.integer("waitSeconds", 0);
        if (wait > 0) service.await(run.id(), Math.min(wait, 600), r -> progress(x, token, r));
        return service.describe(run);
    }

    private Object status(Args a, McpSyncServerExchange x, Object token) throws Exception {
        int wait = Math.min(a.integer("waitSeconds", 0), 600);
        return service.describe(service.await(a.string("runId"), wait, r -> progress(x, token, r)));
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

    private Object overview(Args a, McpSyncServerExchange x, Object token) {
        AnalysisRun run = service.resultsOf(a.string("project"));
        Map<String, Object> out = header(run);
        if (a.string("element") != null) out.put("element", a.string("element"));
        out.putAll(Overview.of(requireResult(run), a.string("element"), Math.max(1, a.integer("limit", 30))));
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

    private Object elementAt(Args a, McpSyncServerExchange x, Object token) {
        AnalysisRun run = service.resultsOf(a.string("project"));
        AnalysisModel.Result result = requireResult(run);
        String file = a.string("file");
        if (file == null) throw new IllegalArgumentException("file is required");
        int line = a.integer("line", -1);
        if (line < 1) throw new IllegalArgumentException("line is required, 1-based");
        int column = a.integer("column", -1);
        List<AnalysisModel.ElementAnnotation> found = Verdicts.at(result, file, line, column < 1 ? null : column);
        Map<String, Object> out = header(run);
        if (found.isEmpty()) {
            out.put("message", "no analysed element at " + file + ":" + line + " (outside every type, or a file"
                               + " that was not analysed)");
            return out;
        }
        out.put("element", Verdicts.explain(result, found.getLast()));
        if (found.size() > 1) {
            out.put("enclosing", found.subList(0, found.size() - 1).stream()
                    .map(e -> e.kind() + " " + e.fqn()).toList());
        }
        return out;
    }

    private Object diffRuns(Args a, McpSyncServerExchange x, Object token) {
        AnalysisRun to = a.string("to") != null ? service.run(a.string("to")) : service.resultsOf(a.string("project"));
        AnalysisRun from = a.string("from") != null ? service.run(a.string("from")) : service.previous(to);
        if (from == null) {
            throw new IllegalStateException("run " + to.id() + " is the project's first: analyze again after an edit,"
                                            + " then compare");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", runHeader(from));
        out.put("to", runHeader(to));
        if (!from.scope().equals(to.scope())) {
            out.put("note", "the runs analysed different parts of the project: elements outside the smaller part"
                            + " appear or disappear for that reason alone");
        }
        Map<String, Object> diff = RunDiff.diff(requireResult(from), requireResult(to), a.string("element"));
        @SuppressWarnings("unchecked")
        List<Object> changed = (List<Object>) diff.remove("changed");
        page(out, "changed", changed, a);
        out.putAll(diff);
        return out;
    }

    private static Map<String, Object> runHeader(AnalysisRun run) {
        Map<String, Object> out = header(run);
        if (!run.scope().isEmpty()) out.put("scope", run.scope());
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
        if (run.finishedAt() != null) out.put("analysedAt", run.finishedAt());
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

        boolean bool(String key) {
            Object value = map.get(key);
            return value instanceof Boolean b ? b : value != null && Boolean.parseBoolean(value.toString());
        }

        List<String> strings(String key) {
            Object value = map.get(key);
            if (value == null) return List.of();
            if (value instanceof List<?> list) return list.stream().map(Object::toString).toList();
            return List.of(value.toString());
        }
    }
}
