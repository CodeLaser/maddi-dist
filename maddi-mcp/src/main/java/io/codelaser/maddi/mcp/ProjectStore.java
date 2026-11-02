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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.inspection.api.resource.BuildFiles;
import io.codelaser.maddi.project.config.CaptureSpec;
import io.codelaser.maddi.project.config.CompileLogCapture;
import io.codelaser.maddi.project.config.ConfiguredBy;
import io.codelaser.maddi.project.config.ProjectConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The configured projects, one JSON file each, so that a configuration outlives the server, and the build file that
 * produced it ({@code gradle clean} deletes {@code build/inputConfiguration.json}). Computing a configuration can
 * take minutes on a large build; with it stored, it is computed again only when the build files say it must be.
 */
public final class ProjectStore {

    /**
     * How to compute the configuration again: run {@code command} in {@code directory}, which rewrites the
     * project's {@code sourceFile}; or capture the build's javac invocations ({@code capture}, maddi-project-config)
     * and make it from them. {@code builtin} {@link #GRADLE} is how a Gradle capture was stored before captures had
     * a spec of their own: the build in {@code directory}.
     */
    public record Regenerate(List<String> command, String directory, String builtin, CaptureSpec capture) {
        /** The stored name of a Gradle capture without a spec. */
        public static final String GRADLE = "gradle";

        public Regenerate(List<String> command, String directory) {
            this(command, directory, null, null);
        }

        public static Regenerate builtin(String builtin, String directory) {
            return new Regenerate(List.of(), directory, builtin, null);
        }

        public static Regenerate capture(CaptureSpec spec) {
            return new Regenerate(List.of(), spec.root().toString(), null, spec);
        }

        /** The capture this regeneration runs; null for a command. */
        public CaptureSpec captureSpec() {
            if (capture != null) return capture;
            return GRADLE.equals(builtin) ? CaptureSpec.gradle(Path.of(directory)) : null;
        }

        public String describe() {
            if (GRADLE.equals(builtin) || capture != null && capture.kind() == ConfiguredBy.GRADLE_CAPTURE) {
                return "gradle build in " + directory + ", every Java source set compiled";
            }
            return capture != null ? capture.describe() : String.join(" ", command);
        }
    }

    /**
     * @param kind       {@code inputConfiguration} (read from {@code sourceFile}, which a build tool wrote),
     *                   {@code directories} (named by hand: nothing can recompute it), or {@code gradle},
     *                   {@code maven} or {@code compileLog} (made by this server by capturing the build's javac
     *                   invocations, into {@code sourceFile} in the store)
     * @param config     null until a captured project's configuration is first made
     * @param daemonJdk  the JDK the daemon runs on for this project; null for the server's. What a project needs can
     *                   differ: an annotation processor (Lombok) runs inside the daemon's javac, and may not support
     *                   the newest JDK
     * @param buildFiles the files that shaped the configuration, hashed when it was made; null when unknown
     * @param regenerate null when there is no way to compute it again
     */
    public record StoredProject(String name, String kind, String sourceFile, Regenerate regenerate,
                               BuildFiles buildFiles, String configuredAt, AnalysisModel.AnalyzeConfig config,
                               String daemonJdk) {
        public static final String INPUT_CONFIGURATION = "inputConfiguration";
        public static final String DIRECTORIES = "directories";
        public static final String GRADLE = "gradle";
        public static final String MAVEN = "maven";
        public static final String COMPILE_LOG = "compileLog";

        public StoredProject(String name, String kind, String sourceFile, Regenerate regenerate, BuildFiles buildFiles,
                             String configuredAt, AnalysisModel.AnalyzeConfig config) {
            this(name, kind, sourceFile, regenerate, buildFiles, configuredAt, config, null);
        }

        public StoredProject with(AnalysisModel.AnalyzeConfig newConfig, BuildFiles newBuildFiles) {
            return new StoredProject(name, kind, sourceFile, regenerate, newBuildFiles,
                    java.time.Instant.now().toString(), newConfig, daemonJdk);
        }

        /** Made again, by a capture that wrote it to {@code file}. */
        public StoredProject with(String file, AnalysisModel.AnalyzeConfig newConfig, BuildFiles newBuildFiles) {
            return new StoredProject(name, kind, file, regenerate, newBuildFiles, java.time.Instant.now().toString(),
                    newConfig, daemonJdk);
        }

        public StoredProject withDaemonJdk(String jdk) {
            return new StoredProject(name, kind, sourceFile, regenerate, buildFiles, configuredAt, config, jdk);
        }
    }

    private final Path directory;
    private final ObjectMapper mapper;

    public ProjectStore(Path directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }

    public Path directory() {
        return directory;
    }

    public void save(StoredProject project) throws IOException {
        Files.createDirectories(directory);
        Path file = fileOf(project.name());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), project);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Every stored project; one that cannot be read is skipped, and named in {@code problems}. */
    public List<StoredProject> loadAll(List<String> problems) {
        List<StoredProject> out = new ArrayList<>();
        if (!Files.isDirectory(directory)) return out;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".project.json")).sorted().toList()) {
                try {
                    out.add(mapper.readValue(file.toFile(), StoredProject.class));
                } catch (IOException e) {
                    problems.add(file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            problems.add(directory + ": " + e.getMessage());
        }
        return out;
    }

    /** Store an ended run, and drop the project's oldest beyond {@code keep}. */
    public void saveRun(AnalysisRun.Snapshot run, int keep) throws IOException {
        Path dir = runsOf(run.project());
        Files.createDirectories(dir);
        Path file = dir.resolve(safe(run.id()) + ".json");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writeValue(tmp.toFile(), run);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        List<Path> stored = runFiles(dir);
        for (int i = 0; i < stored.size() - keep; i++) Files.deleteIfExists(stored.get(i));
    }

    /** Every stored run, oldest first; one that cannot be read is skipped, and named in {@code problems}. */
    public List<AnalysisRun.Snapshot> loadRuns(List<String> problems) {
        List<AnalysisRun.Snapshot> out = new ArrayList<>();
        Path runs = directory.resolve("runs");
        if (!Files.isDirectory(runs)) return out;
        try (var projects = Files.list(runs)) {
            for (Path dir : projects.filter(Files::isDirectory).toList()) {
                for (Path file : runFiles(dir)) {
                    try {
                        out.add(mapper.readValue(file.toFile(), AnalysisRun.Snapshot.class));
                    } catch (IOException e) {
                        problems.add(file + ": " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            problems.add(runs + ": " + e.getMessage());
        }
        out.sort(java.util.Comparator.comparing(r -> r.finishedAt() == null ? "" : r.finishedAt()));
        return out;
    }

    /* oldest first, by the run's number: run-9 before run-10 */
    private static List<Path> runFiles(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().matches("run-\\d+\\.json"))
                    .sorted(java.util.Comparator.comparingLong(ProjectStore::runNumber)).toList();
        }
    }

    /** The number of a run id ({@code run-12}: 12), or 0. */
    public static long runNumber(Path file) {
        return runNumber(file.getFileName().toString().replace(".json", ""));
    }

    public static long runNumber(String runId) {
        try {
            return Long.parseLong(runId.substring(runId.lastIndexOf('-') + 1));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private Path runsOf(String project) {
        return directory.resolve("runs").resolve(safe(project));
    }

    /**
     * Where this server writes for a project when it captures its build: the compile and build logs, and the
     * configuration it makes from them ({@link #inputConfigurationOf}).
     */
    public Path workDirectoryOf(String name) {
        return directory.resolve("work").resolve(safe(name));
    }

    /** Where a project's configuration goes when this server makes it. */
    public Path inputConfigurationOf(String name) {
        return workDirectoryOf(name).resolve(CompileLogCapture.INPUT_CONFIGURATION);
    }

    /** Where the output of a project's regeneration goes. */
    public Path logOf(String name) {
        return workDirectoryOf(name).resolve(ProjectConfigurator.REGENERATE_LOG);
    }

    private Path fileOf(String name) {
        return directory.resolve(safe(name) + ".project.json");
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
