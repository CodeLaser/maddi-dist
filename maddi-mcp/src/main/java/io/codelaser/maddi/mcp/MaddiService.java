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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.ide.client.MaddiDaemonProcess;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * What the MCP tools do, without MCP: the configured projects, the daemon, and the runs. One daemon serves every
 * project, and it takes one request at a time, so there is at most one run going on.
 */
public final class MaddiService implements Closeable {

    /** How the daemon is launched. {@code xmxMb} 0: the JVM's default heap. */
    public record DaemonSettings(Path installDir, Path jdkHome, int xmxMb, Path logFile) {
    }

    private final DaemonSettings settings;
    private final MaddiDaemonProcess daemon = new MaddiDaemonProcess();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "maddi-mcp-run");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger runCounter = new AtomicInteger();
    private final Map<String, AnalysisModel.AnalyzeConfig> projects = new LinkedHashMap<>();
    private final Map<String, AnalysisRun> lastRuns = new LinkedHashMap<>();
    private final Map<String, AnalysisRun> runs = new LinkedHashMap<>();
    private AnalysisRun current;

    public MaddiService(DaemonSettings settings) {
        this.settings = settings;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ---- projects ----

    public synchronized Map<String, Object> configure(String name, AnalysisModel.AnalyzeConfig config)
            throws IOException {
        ProjectConfigs.check(config);
        if (config.jmods() == null || config.jmods().isEmpty()) {
            Path jdk = config.sdkHome() != null ? Path.of(config.sdkHome()) : settings.jdkHome();
            config = new AnalysisModel.AnalyzeConfig(config.workingDirectory(), config.sdkHome(),
                    config.sourceEncoding(), ProjectConfigs.jdkModules(jdk), config.sources(), config.classpath(),
                    config.restrictToPackages(), config.parallel(), config.warnNearMisses(),
                    config.moduleSourceSets());
        }
        projects.put(name, config);
        lastRuns.remove(name); // its verdicts were of another configuration
        return describeProject(name, config);
    }

    public synchronized List<Map<String, Object>> projects() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var entry : projects.entrySet()) {
            Map<String, Object> m = describeProject(entry.getKey(), entry.getValue());
            AnalysisRun run = lastRuns.get(entry.getKey());
            if (run != null) m.put("lastRun", run.id() + " " + run.state() + " " + run.certainty());
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> describeProject(String name, AnalysisModel.AnalyzeConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", name);
        out.put("workingDirectory", config.workingDirectory());
        if (config.sdkHome() != null) out.put("sdkHome", config.sdkHome());
        List<Map<String, Object>> sets = new ArrayList<>();
        for (AnalysisModel.ModuleSourceSet set : config.moduleSourceSets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", set.name());
            if (set.test()) m.put("test", true);
            m.put("sourceDirectories", set.sourceDirectories());
            if (!set.dependencies().isEmpty()) m.put("dependsOn", set.dependencies());
            m.put("classPathEntries", set.classPath().size());
            sets.add(m);
        }
        out.put("sourceSets", sets);
        out.put("jdkModules", config.jmods().size());
        return out;
    }

    // ---- runs ----

    /** Start an analysis of (part of) a project; returns at once with the run, which goes on in the background. */
    public synchronized AnalysisRun analyze(String project, List<String> sourceSets, List<String> packages) {
        AnalysisModel.AnalyzeConfig config = projects.get(project);
        if (config == null) throw new IllegalArgumentException("no project '" + project + "'; configure it first");
        if (current != null && current.state() == AnalysisRun.State.RUNNING) {
            throw new IllegalStateException("run " + current.id() + " of project " + current.project()
                                            + " is still going on: wait for it, or stop it");
        }
        AnalysisModel.AnalyzeConfig request = ProjectConfigs.part(config, sourceSets, packages);
        AnalysisRun run = new AnalysisRun("run-" + runCounter.incrementAndGet(), project);
        current = run;
        runs.put(run.id(), run);
        lastRuns.put(project, run);
        runner.execute(() -> execute(run, request));
        return run;
    }

    private void execute(AnalysisRun run, AnalysisModel.AnalyzeConfig request) {
        try {
            daemon.ensureStarted(settings.installDir(), settings.jdkHome(), settings.xmxMb(), settings.logFile());
            JsonNode end = daemon.analyze(run.id(), request, frame -> run.onFrame(frame, mapper));
            run.onEnd(end, mapper);
        } catch (IOException | RuntimeException e) {
            run.fail(e.getMessage() == null ? e.toString() : e.getMessage()); // after a stop: already STOPPED
        }
    }

    /**
     * The run, waited for up to {@code waitSeconds}; {@code onProgress} hears about it every few seconds meanwhile.
     */
    public AnalysisRun await(String runId, int waitSeconds, Consumer<AnalysisRun> onProgress)
            throws InterruptedException {
        AnalysisRun run = run(runId);
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0 || run.awaitEnd(Math.min(left, 5_000))) return run;
            if (onProgress != null) onProgress.accept(run);
        }
    }

    public synchronized AnalysisRun run(String runId) {
        if (runId == null) {
            if (current == null) throw new IllegalArgumentException("no run yet");
            return current;
        }
        AnalysisRun run = runs.get(runId);
        if (run == null) throw new IllegalArgumentException("no run '" + runId + "'");
        return run;
    }

    /** Stop a run: the daemon is killed, which is how a run is stopped; the next run launches a fresh one. */
    public AnalysisRun stop(String runId) {
        AnalysisRun run = run(runId);
        if (run.state() != AnalysisRun.State.RUNNING) return run;
        run.stop();
        daemon.kill();
        return run;
    }

    /** The run whose verdicts a query reads: the project's last, finished or not. */
    public synchronized AnalysisRun resultsOf(String project) {
        if (project == null) {
            if (lastRuns.size() == 1) return lastRuns.values().iterator().next();
            if (current != null) return current;
            throw new IllegalArgumentException("no analysis yet");
        }
        AnalysisRun run = lastRuns.get(project);
        if (run == null) throw new IllegalArgumentException("project '" + project + "' has not been analysed");
        return run;
    }

    /** What the daemon's last analysis knows of these library types. */
    public JsonNode libraryAnnotations(List<String> types) throws IOException {
        synchronized (this) {
            if (current != null && current.state() == AnalysisRun.State.RUNNING) {
                throw new IllegalStateException("run " + current.id() + " is going on; the daemon answers after it");
            }
            if (current == null || !daemon.isAlive()) {
                throw new IllegalStateException("library values come from the daemon's last analysis: run one first");
            }
        }
        return daemon.libraryAnnotations("lib-" + System.nanoTime(), types);
    }

    @Override
    public void close() {
        daemon.close();
        runner.shutdownNow();
    }
}
