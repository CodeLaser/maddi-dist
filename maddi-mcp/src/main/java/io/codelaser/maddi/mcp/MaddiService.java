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
import io.codelaser.maddi.project.config.BuildFileCheck;
import io.codelaser.maddi.project.config.CaptureListener;
import io.codelaser.maddi.project.config.CaptureResult;
import io.codelaser.maddi.project.config.CaptureSpec;
import io.codelaser.maddi.project.config.CompileLogCapture;

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
        long highest = 0;
        for (AnalysisRun.Snapshot snapshot : store.loadRuns(problems)) {
            AnalysisRun run = AnalysisRun.restore(snapshot);
            runs.put(run.id(), run);
            lastRuns.put(run.project(), run); // oldest first: the last one put is the latest
            highest = Math.max(highest, ProjectStore.runNumber(run.id()));
        }
        runCounter.set((int) highest); // a new run never takes a stored run's id
        for (String problem : problems) System.err.println("maddi-mcp: cannot read stored " + problem);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ---- projects ----

    /** Configure (or configure again) a project, and store it. */
    public synchronized Map<String, Object> configure(ProjectStore.StoredProject project) throws IOException {
        ProjectStore.StoredProject previous = projects.get(project.name());
        if (project.daemonJdk() == null && previous != null && previous.daemonJdk() != null) {
            project = project.withDaemonJdk(previous.daemonJdk()); // configured again: the JDK it needs stays
        }
        if (project.daemonJdk() != null) checkJdk(Path.of(project.daemonJdk()));
        ProjectStore.StoredProject complete = project.config() == null ? project
                : project.with(withJdkModules(project.config(), project.daemonJdk()), project.buildFiles());
        if (complete.config() != null) ProjectConfigs.check(complete.config());
        store.save(complete);
        projects.put(complete.name(), complete);
        return describeProject(complete, ProjectConfigs.freshness(complete.buildFiles(), complete.config()));
    }

    /** Run this project's daemon on another JDK (null: the server's), keeping its configuration. */
    public synchronized Map<String, Object> setDaemonJdk(String name, String jdk) throws IOException {
        ProjectStore.StoredProject project = projects.get(name);
        if (project == null) throw new IllegalArgumentException("no project '" + name + "'; configure it first");
        if (jdk != null) checkJdk(Path.of(jdk));
        ProjectStore.StoredProject changed = project.withDaemonJdk(jdk);
        store.save(changed);
        projects.put(name, changed);
        return describeProject(changed, ProjectConfigs.freshness(changed.buildFiles(), changed.config()));
    }

    /** The JDK the daemon runs on for this project. */
    public Path daemonJdkOf(ProjectStore.StoredProject project) {
        return project.daemonJdk() != null ? Path.of(project.daemonJdk()) : settings.jdkHome();
    }

    private static void checkJdk(Path jdk) {
        int feature = McpMain.featureVersion(jdk);
        if (feature < 25) {
            throw new IllegalArgumentException("the daemon needs a JDK 25 or later; " + jdk + " is "
                                               + (feature < 0 ? "not a JDK (no release file)" : "JDK " + feature));
        }
    }

    private AnalysisModel.AnalyzeConfig withJdkModules(AnalysisModel.AnalyzeConfig config, String daemonJdk)
            throws IOException {
        if (config.jmods() != null && !config.jmods().isEmpty()) return config;
        Path jdk = config.sdkHome() != null ? Path.of(config.sdkHome())
                : daemonJdk != null ? Path.of(daemonJdk) : settings.jdkHome();
        return new AnalysisModel.AnalyzeConfig(config.workingDirectory(), config.sdkHome(), config.sourceEncoding(),
                ProjectConfigs.jdkModules(jdk), config.sources(), config.classpath(), config.restrictToPackages(),
                config.parallel(), config.warnNearMisses(), config.moduleSourceSets());
    }

    /** The configured projects, each with whether its build files still match its configuration. */
    public synchronized List<Map<String, Object>> projects() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProjectStore.StoredProject project : projects.values()) {
            Map<String, Object> m = describeProject(project,
                    ProjectConfigs.freshness(project.buildFiles(), project.config()));
            AnalysisRun run = lastRuns.get(project.name());
            if (run != null) {
                m.put("lastRun", run.id() + " " + run.state() + " " + run.certainty()
                                 + (run.finishedAt() == null ? "" : " at " + run.finishedAt()));
            }
            out.add(m);
        }
        return out;
    }

    /** Where this server writes the configuration it makes for a project. */
    public Path inputConfigurationOf(String name) {
        return store.inputConfigurationOf(name);
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
        if (project.daemonJdk() != null) out.put("daemonJdk", project.daemonJdk());
        out.put("regenerate", project.regenerate() == null ? "none: configure again when the build changes"
                : project.regenerate().describe());
        if (config == null) {
            out.put("configuration", "not made yet: the next analyze makes it first (or analyze with configureOnly)."
                                     + " It compiles every Java source set of the build, which takes a while");
            return out;
        }
        out.putAll(freshness.describe());
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
        return analyze(project, sourceSets, packages, allowStale, false);
    }

    /** @param configureOnly bring the configuration up to date, and stop there */
    public synchronized AnalysisRun analyze(String project, List<String> sourceSets, List<String> packages,
                                            boolean allowStale, boolean configureOnly) {
        if (project == null) project = projects.size() == 1 ? projects.keySet().iterator().next() : "default";
        ProjectStore.StoredProject stored = projects.get(project);
        if (stored == null) throw new IllegalArgumentException("no project '" + project + "'; configure it first");
        if (current != null && current.state() == AnalysisRun.State.RUNNING) {
            throw new IllegalStateException("run " + current.id() + " of project " + current.project()
                                            + " is still going on: wait for it, or stop it");
        }
        // refuses unknown source sets now, not after a long capture; a configuration not made yet is checked then
        if (stored.config() != null) ProjectConfigs.part(stored.config(), sourceSets, packages);
        Map<String, Object> scope = new LinkedHashMap<>();
        if (!sourceSets.isEmpty()) scope.put("sourceSets", List.copyOf(sourceSets));
        if (!packages.isEmpty()) scope.put("packages", List.copyOf(packages));
        AnalysisRun run = new AnalysisRun("run-" + runCounter.incrementAndGet(), project, scope);
        current = run;
        runs.put(run.id(), run);
        forgetOldRuns();
        if (!configureOnly) lastRuns.put(project, run); // a run without verdicts hides none of the last one's
        runner.execute(() -> execute(run, stored, sourceSets, packages, allowStale, configureOnly));
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
                         List<String> packages, boolean allowStale, boolean configureOnly) {
        try {
            ProjectStore.StoredProject project = upToDate(run, stored, allowStale);
            if (project == null) return; // the run has failed, saying why
            if (configureOnly) {
                run.configured();
                return;
            }
            // an IDE explains only a type close to @Container; asked here, every non-container is explained
            AnalysisModel.AnalyzeConfig request = ProjectConfigs.part(project.config(), sourceSets, packages)
                    .withContainerWhyMaxSlots(Integer.MAX_VALUE);
            run.phase("analyze", "starting the daemon on " + daemonJdkOf(project));
            daemon.ensureStarted(settings.installDir(), daemonJdkOf(project), settings.xmxMb(), settings.logFile());
            JsonNode end = daemon.analyze(run.id(), request, frame -> run.onFrame(frame, mapper));
            run.onEnd(end, mapper);
        } catch (IOException | RuntimeException e) {
            run.fail(e.getMessage() == null ? e.toString() : e.getMessage()); // after a stop: already STOPPED
        }
        persist(run);
    }

    /* a run with values outlives the server: finished, or stopped after some passes; a failed one has nothing */
    private void persist(AnalysisRun run) {
        AnalysisRun.Snapshot snapshot = run.snapshot();
        if (snapshot.result() == null || snapshot.state() == AnalysisRun.State.RUNNING
            || snapshot.state() == AnalysisRun.State.FAILED) {
            return;
        }
        try {
            store.saveRun(snapshot, KEPT_RUNS);
        } catch (IOException | RuntimeException e) {
            System.err.println("maddi-mcp: cannot store " + run.id() + ": " + e.getMessage());
        }
    }

    /**
     * A run as the tools describe it, with, once it has ended, how many of the project's source files changed since:
     * a stored result survives a restart, and a week-old result must not pass for one of the code as it is now.
     */
    public Map<String, Object> describe(AnalysisRun run) {
        Map<String, Object> out = run.describe();
        ProjectStore.StoredProject project;
        synchronized (this) {
            project = projects.get(run.project());
        }
        if (run.finishedAt() != null && run.result() != null && project != null && project.config() != null) {
            int changed = sourcesChangedSince(project.config(), java.time.Instant.parse(run.finishedAt()));
            out.put("sourcesChangedSince", changed);
            String hint = jdkHint(run.result());
            if (hint != null) out.put("hint", hint);
            if (changed > 0) {
                out.put("sourcesNote", changed + " source file(s) changed since this run: analyze again for verdicts"
                                       + " of the code as it is now");
            }
        }
        return out;
    }

    /*
     An annotation processor that cannot run on the daemon's JDK (Lombok on a JDK newer than it knows) leaves its
     source set parsed without the members it generates: the daemon says so per source set, in these words.
     */
    private static String jdkHint(AnalysisModel.Result result) {
        if (result.findings() == null) return null;
        for (AnalysisModel.Finding f : result.findings()) {
            if ("parse".equals(f.category()) && f.message() != null && f.message().contains(" failed on JDK ")) {
                return "an annotation processor could not run on the daemon's JDK, so source sets were parsed without"
                       + " the members it generates: give the project a JDK it supports, configure_project with"
                       + " project and daemonJdk (a JDK 25 or later), and analyze again";
            }
        }
        return null;
    }

    private static int sourcesChangedSince(AnalysisModel.AnalyzeConfig config, java.time.Instant since) {
        int[] count = {0};
        for (AnalysisModel.ModuleSourceSet set : config.moduleSourceSets()) {
            for (String dir : set.sourceDirectories()) {
                Path root = Path.of(dir);
                if (!java.nio.file.Files.isDirectory(root)) continue;
                try (var files = java.nio.file.Files.walk(root)) {
                    files.filter(f -> {
                        String name = f.getFileName().toString();
                        return name.endsWith(".java") || name.endsWith(".kt");
                    }).forEach(f -> {
                        try {
                            if (java.nio.file.Files.getLastModifiedTime(f).toInstant().isAfter(since)) count[0]++;
                        } catch (IOException e) {
                            // a file that went while counting: not one to count
                        }
                    });
                } catch (IOException | java.io.UncheckedIOException e) {
                    // an unreadable directory changes no count worth failing for
                }
            }
        }
        return count[0];
    }

    /**
     * The project's configuration, computed again first when its build files changed and it knows how; null when the
     * run cannot go on, having failed it.
     */
    private ProjectStore.StoredProject upToDate(AnalysisRun run, ProjectStore.StoredProject project,
                                                boolean allowStale) throws IOException {
        if (project.config() == null) {
            return regenerate(run, project, "made, for the first time");
        }
        run.phase("configure", "checking the build files");
        BuildFileCheck.Freshness freshness = ProjectConfigs.freshness(project.buildFiles(), project.config());
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
        return regenerate(run, project, "regenerated, as the build changed: " + changed);
    }

    /* the configuration made again, by the project's command or by this server; null when the run failed */
    private ProjectStore.StoredProject regenerate(AnalysisRun run, ProjectStore.StoredProject project, String why)
            throws IOException {
        ProjectStore.Regenerate regenerate = project.regenerate();
        CaptureSpec capture = regenerate.captureSpec();
        Path file;
        if (capture != null) {
            file = capture(run, project, capture);
            if (file == null) return null;
        } else {
            if (!runCommand(run, regenerate, store.logOf(project.name()))) return null;
            file = Path.of(project.sourceFile());
        }
        ProjectStore.StoredProject fresh = project.with(file.toString(),
                withJdkModules(ProjectConfigs.fromInputConfiguration(file, mapper), project.daemonJdk()),
                ProjectConfigs.buildFilesOf(file, mapper));
        ProjectConfigs.check(fresh.config());
        synchronized (this) {
            store.save(fresh);
            projects.put(fresh.name(), fresh);
        }
        BuildFileCheck.Freshness after = ProjectConfigs.freshness(fresh.buildFiles(), fresh.config());
        run.configuration(why + (after.state() == BuildFileCheck.State.STALE
                ? "; its build files still differ from the recorded ones (" + after.changed().size()
                  + "), so it will be regenerated on every run until the producer records them" : ""));
        return fresh;
    }

    /* the build compiled with javac's arguments logged, and the log made a configuration; null when the run failed */
    private Path capture(AnalysisRun run, ProjectStore.StoredProject project, CaptureSpec spec) throws IOException {
        run.phase("configure", "compiling every Java source set of the build in " + spec.root());
        CaptureListener listener = new CaptureListener() {
            @Override
            public void progress(String message) {
                run.phase("configure", "compiling the build: " + message);
            }

            @Override
            public void process(Process process) {
                run.attach(process);
            }
        };
        CaptureResult result;
        try {
            result = CompileLogCapture.capture(spec, store.workDirectoryOf(project.name()), List.of(), listener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.fail("interrupted while compiling the build");
            return null;
        } catch (IOException | RuntimeException e) {
            if (run.state() == AnalysisRun.State.RUNNING) run.fail(e.getMessage());
            return null;
        }
        if (run.state() != AnalysisRun.State.RUNNING) return null; // stopped
        return result.inputConfiguration();
    }

    /* the project's own command; false when the run failed */
    private boolean runCommand(AnalysisRun run, ProjectStore.Regenerate regenerate, Path log) throws IOException {
        run.phase("configure", "regenerating the configuration: " + regenerate.describe());
        java.nio.file.Files.createDirectories(log.getParent());
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
            return false;
        } finally {
            run.attach(null);
        }
        if (run.state() != AnalysisRun.State.RUNNING) return false; // stopped
        if (exit != 0) {
            run.fail("regenerating the configuration failed (exit " + exit + ", " + regenerate.describe()
                     + "); its output is in " + log);
            return false;
        }
        return true;
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
            if (current != null) return current;
            AnalysisRun latest = null;
            for (AnalysisRun run : runs.values()) latest = run;
            if (latest == null) throw new IllegalArgumentException("no run yet");
            return latest;
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
            // after a restart: the latest stored run, whichever project
            AnalysisRun latest = null;
            for (AnalysisRun run : runs.values()) latest = run;
            if (latest == null) throw new IllegalArgumentException("no analysis yet");
            return latest;
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
