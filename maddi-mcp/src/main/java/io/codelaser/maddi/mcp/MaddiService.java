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
    private final ProjectStore store;
    private final Map<String, ProjectStore.StoredProject> projects = new LinkedHashMap<>();
    private final Map<String, AnalysisRun> lastRuns = new LinkedHashMap<>();
    private final Map<String, AnalysisRun> runs = new LinkedHashMap<>();
    private AnalysisRun current;

    /** @param storeDirectory where the configured projects are kept; those already there are loaded */
    public MaddiService(DaemonSettings settings, Path storeDirectory) {
        this.settings = settings;
        this.store = new ProjectStore(storeDirectory, mapper);
        List<String> problems = new ArrayList<>();
        for (ProjectStore.StoredProject p : store.loadAll(problems)) projects.put(p.name(), p);
        for (String problem : problems) System.err.println("maddi-mcp: cannot read stored project " + problem);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ---- projects ----

    /** Configure (or configure again) a project, and store it. */
    public synchronized Map<String, Object> configure(ProjectStore.StoredProject project) throws IOException {
        ProjectStore.StoredProject complete = project.with(withJdkModules(project.config()), project.buildFiles());
        ProjectConfigs.check(complete.config());
        store.save(complete);
        projects.put(complete.name(), complete);
        lastRuns.remove(complete.name()); // its verdicts were of another configuration
        return describeProject(complete, BuildFileCheck.check(complete.buildFiles(), complete.config()));
    }

    private AnalysisModel.AnalyzeConfig withJdkModules(AnalysisModel.AnalyzeConfig config) throws IOException {
        if (config.jmods() != null && !config.jmods().isEmpty()) return config;
        Path jdk = config.sdkHome() != null ? Path.of(config.sdkHome()) : settings.jdkHome();
        return new AnalysisModel.AnalyzeConfig(config.workingDirectory(), config.sdkHome(), config.sourceEncoding(),
                ProjectConfigs.jdkModules(jdk), config.sources(), config.classpath(), config.restrictToPackages(),
                config.parallel(), config.warnNearMisses(), config.moduleSourceSets());
    }

    /** The configured projects, each with whether its build files still match its configuration. */
    public synchronized List<Map<String, Object>> projects() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProjectStore.StoredProject project : projects.values()) {
            Map<String, Object> m = describeProject(project,
                    BuildFileCheck.check(project.buildFiles(), project.config()));
            AnalysisRun run = lastRuns.get(project.name());
            if (run != null) m.put("lastRun", run.id() + " " + run.state() + " " + run.certainty());
            out.add(m);
        }
        return out;
    }

    public Path storeDirectory() {
        return store.directory();
    }

    private static Map<String, Object> describeProject(ProjectStore.StoredProject project,
                                                       BuildFileCheck.Freshness freshness) {
        AnalysisModel.AnalyzeConfig config = project.config();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project.name());
        out.put("from", project.sourceFile() != null ? project.sourceFile() : project.kind());
        out.put("configuredAt", project.configuredAt());
        out.putAll(freshness.describe());
        out.put("regenerate", project.regenerate() == null ? "none: configure again when the build changes"
                : String.join(" ", project.regenerate().command()));
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

    /**
     * Start an analysis of (part of) a project; returns at once with the run, which goes on in the background.
     * {@code project} null: the only project configured, or else the one named {@code default}.
     */
    public synchronized AnalysisRun analyze(String project, List<String> sourceSets, List<String> packages,
                                            boolean allowStale) {
        if (project == null) project = projects.size() == 1 ? projects.keySet().iterator().next() : "default";
        ProjectStore.StoredProject stored = projects.get(project);
        if (stored == null) throw new IllegalArgumentException("no project '" + project + "'; configure it first");
        if (current != null && current.state() == AnalysisRun.State.RUNNING) {
            throw new IllegalStateException("run " + current.id() + " of project " + current.project()
                                            + " is still going on: wait for it, or stop it");
        }
        ProjectConfigs.part(stored.config(), sourceSets, packages); // refuses unknown source sets now, not later
        Map<String, Object> scope = new LinkedHashMap<>();
        if (!sourceSets.isEmpty()) scope.put("sourceSets", List.copyOf(sourceSets));
        if (!packages.isEmpty()) scope.put("packages", List.copyOf(packages));
        AnalysisRun run = new AnalysisRun("run-" + runCounter.incrementAndGet(), project, scope);
        current = run;
        runs.put(run.id(), run);
        forgetOldRuns();
        lastRuns.put(project, run);
        runner.execute(() -> execute(run, stored, sourceSets, packages, allowStale));
        return run;
    }

    /*
     A result holds every element of a project, which on a large one is not small: keep the last few runs to compare,
     and always each project's latest.
     */
    private void forgetOldRuns() {
        var it = runs.values().iterator();
        while (runs.size() > KEPT_RUNS && it.hasNext()) {
            AnalysisRun run = it.next();
            if (run != current && !lastRuns.containsValue(run)) it.remove();
        }
    }

    private static final int KEPT_RUNS = 8;

    /** The project's run before this one, among those kept; null if there is none. */
    public synchronized AnalysisRun previous(AnalysisRun run) {
        AnalysisRun previous = null;
        for (AnalysisRun r : runs.values()) {
            if (r == run) return previous;
            if (r.project().equals(run.project())) previous = r;
        }
        return null;
    }

    private void execute(AnalysisRun run, ProjectStore.StoredProject stored, List<String> sourceSets,
                         List<String> packages, boolean allowStale) {
        try {
            ProjectStore.StoredProject project = upToDate(run, stored, allowStale);
            if (project == null) return; // the run has failed, saying why
            AnalysisModel.AnalyzeConfig request = ProjectConfigs.part(project.config(), sourceSets, packages);
            run.phase("analyze", "starting the daemon");
            daemon.ensureStarted(settings.installDir(), settings.jdkHome(), settings.xmxMb(), settings.logFile());
            JsonNode end = daemon.analyze(run.id(), request, frame -> run.onFrame(frame, mapper));
            run.onEnd(end, mapper);
        } catch (IOException | RuntimeException e) {
            run.fail(e.getMessage() == null ? e.toString() : e.getMessage()); // after a stop: already STOPPED
        }
    }

    /**
     * The project's configuration, computed again first when its build files changed and it knows how; null when the
     * run cannot go on, having failed it.
     */
    private ProjectStore.StoredProject upToDate(AnalysisRun run, ProjectStore.StoredProject project,
                                                boolean allowStale) throws IOException {
        run.phase("configure", "checking the build files");
        BuildFileCheck.Freshness freshness = BuildFileCheck.check(project.buildFiles(), project.config());
        if (freshness.state() != BuildFileCheck.State.STALE) {
            run.configuration(freshness.state() == BuildFileCheck.State.FRESH ? "stored, build files unchanged"
                    : "stored; no build files recorded, so not checked");
            return project;
        }
        String changed = String.join(", ", freshness.changed().subList(0, Math.min(5, freshness.changed().size())))
                         + (freshness.changed().size() > 5 ? ", ..." : "");
        if (project.regenerate() == null) {
            if (allowStale) {
                run.configuration("STALE, analysed anyway: " + changed);
                return project;
            }
            run.fail("the build files changed since the project was configured (" + changed + ") and this project"
                     + " has no command to compute its configuration again: configure it again, or pass allowStale");
            return null;
        }
        run.phase("configure", "regenerating the configuration: " + changed + " changed");
        Path log = store.logOf(project.name());
        ProjectStore.Regenerate regenerate = project.regenerate();
        Process process = new ProcessBuilder(regenerate.command())
                .directory(regenerate.directory() == null ? null : Path.of(regenerate.directory()).toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        run.attach(process);
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            run.fail("interrupted while regenerating the configuration");
            return null;
        } finally {
            run.attach(null);
        }
        if (run.state() != AnalysisRun.State.RUNNING) return null; // stopped
        if (exit != 0) {
            run.fail("regenerating the configuration failed (exit " + exit + ", " + String.join(" ", regenerate.command())
                     + "); its output is in " + log);
            return null;
        }
        Path file = Path.of(project.sourceFile());
        ProjectStore.StoredProject fresh = project.with(
                withJdkModules(ProjectConfigs.fromInputConfiguration(file, mapper)),
                ProjectConfigs.buildFilesOf(file, mapper));
        ProjectConfigs.check(fresh.config());
        synchronized (this) {
            store.save(fresh);
            projects.put(fresh.name(), fresh);
        }
        BuildFileCheck.Freshness after = BuildFileCheck.check(fresh.buildFiles(), fresh.config());
        run.configuration("regenerated, as " + changed + " changed"
                          + (after.state() == BuildFileCheck.State.STALE
                ? "; its build files still differ from the recorded ones (" + after.changed().size()
                  + "), so it will be regenerated on every run until the producer records them" : ""));
        return fresh;
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
        run.stop(); // kills the regeneration, if that is what is going on
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
