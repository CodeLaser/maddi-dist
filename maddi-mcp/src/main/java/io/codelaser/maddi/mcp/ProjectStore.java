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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.inspection.api.resource.BuildFiles;
import io.codelaser.maddi.project.config.CaptureSpec;
import io.codelaser.maddi.project.config.CompileLogCapture;
import io.codelaser.maddi.project.config.ConfiguredBy;
import io.codelaser.maddi.project.config.ProjectRecord;
import io.codelaser.maddi.project.config.ProjectRecords;
import io.codelaser.maddi.project.config.Regenerate;

/**
 * The configured projects, one JSON file each, so that a configuration outlives the server, and the build file that
 * produced it ({@code gradle clean} deletes {@code build/inputConfiguration.json}). Computing a configuration can
 * take minutes on a large build; with it stored, it is computed again only when the build files say it must be.
 * <p>
 * As maddi-project-config's {@link ProjectRecords}, it keeps a {@link ProjectRecord} per project; beside it, what
 * the daemon needs: the {@link AnalysisModel.AnalyzeConfig} made from the record's configuration, and the JDK.
 */
public final class ProjectStore implements ProjectRecords {

    /**
     * @param kind       {@code inputConfiguration} (read from {@code sourceFile}, which a build tool wrote),
     *                   {@code directories} (named by hand: nothing can recompute it), or {@code gradle},
     *                   {@code maven} or {@code compileLog} (made by this server by capturing the build's javac
     *                   invocations, into {@code sourceFile} in the store)
     * @param sourceFile the record's configuration; null for a {@code directories} project stored before this server
     *                   wrote one for it, which is checked against {@code buildFiles} instead
     * @param config     made from {@code sourceFile}; null until it is made (a capture not run yet), and again after
     *                   the file is made anew, until the server has read it
     * @param daemonJdk  the JDK the daemon runs on for this project; null for the server's. What a project needs can
     *                   differ: an annotation processor (Lombok) runs inside the daemon's javac, and may not support
     *                   the newest JDK
     * @param buildFiles the build files {@code sourceFile} recorded when {@code config} was read from it: when they
     *                   differ, a build tool wrote it again, and it is read again; null when unknown
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

        /** The record as maddi-project-config knows it, with {@link #config} still to be made. */
        static StoredProject of(ProjectRecord record, String daemonJdk) {
            return new StoredProject(record.name(), kindOf(record.configuredBy()),
                    record.inputConfiguration() == null ? null : record.inputConfiguration().toString(),
                    record.regenerate(), null, record.configuredAt().toString(), null, daemonJdk);
        }

        public ProjectRecord record() {
            return new ProjectRecord(name, configuredBy(kind), sourceFile == null ? null : Path.of(sourceFile),
                    regenerate, configuredAt == null ? Instant.EPOCH : Instant.parse(configuredAt));
        }

        /** Its configuration as read from {@code sourceFile}, which recorded {@code fileBuildFiles}. */
        public StoredProject with(AnalysisModel.AnalyzeConfig newConfig, BuildFiles fileBuildFiles) {
            return new StoredProject(name, kind, sourceFile, regenerate, fileBuildFiles, configuredAt, newConfig,
                    daemonJdk);
        }

        public StoredProject withDaemonJdk(String jdk) {
            return new StoredProject(name, kind, sourceFile, regenerate, buildFiles, configuredAt, config, jdk);
        }

        private static String kindOf(ConfiguredBy by) {
            return switch (by) {
                case FILE -> INPUT_CONFIGURATION;
                case DIRECTORIES -> DIRECTORIES;
                case GRADLE_CAPTURE -> GRADLE;
                case MAVEN_CAPTURE -> MAVEN;
                case COMPILE_LOG -> COMPILE_LOG;
                case DISCOVER -> by.key();
            };
        }

        private static ConfiguredBy configuredBy(String kind) {
            return switch (kind) {
                case INPUT_CONFIGURATION -> ConfiguredBy.FILE;
                case DIRECTORIES -> ConfiguredBy.DIRECTORIES;
                case GRADLE -> ConfiguredBy.GRADLE_CAPTURE;
                case MAVEN -> ConfiguredBy.MAVEN_CAPTURE;
                case COMPILE_LOG -> ConfiguredBy.COMPILE_LOG;
                default -> ConfiguredBy.ofKey(kind);
            };
        }
    }

    private final Path directory;
    private final ObjectMapper mapper;

    public ProjectStore(Path directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).registerModule(new SimpleModule("maddi-mcp-store")
                .addSerializer(Regenerate.class, new RegenerateSerializer())
                .addDeserializer(Regenerate.class, new RegenerateDeserializer()));
    }

    public Path directory() {
        return directory;
    }

    // ---- ProjectRecords ----

    @Override
    public Optional<ProjectRecord> load(String name) throws IOException {
        return loadStored(name).map(StoredProject::record);
    }

    @Override
    public List<ProjectRecord> loadAll(List<String> problems) {
        return loadAllStored(problems).stream().map(StoredProject::record).toList();
    }

    /** A record made (again) by maddi-project-config: its configuration is to be read anew; the JDK stays. */
    @Override
    public void save(ProjectRecord record) throws IOException {
        String daemonJdk = loadStored(record.name()).map(StoredProject::daemonJdk).orElse(null);
        save(StoredProject.of(record, daemonJdk));
    }

    @Override
    public Path workDirectory(String name) {
        return directory.resolve("work").resolve(safe(name));
    }

    // ---- the stored form ----

    public Optional<StoredProject> loadStored(String name) throws IOException {
        Path file = fileOf(name);
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(mapper.readValue(file.toFile(), StoredProject.class));
    }

    public void save(StoredProject project) throws IOException {
        Files.createDirectories(directory);
        Path file = fileOf(project.name());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), project);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Every stored project; one that cannot be read is skipped, and named in {@code problems}. */
    public List<StoredProject> loadAllStored(List<String> problems) {
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

    /*
     A regeneration as this store has always written it: {command, directory, capture}, the directory a plain path.
     Stored projects from before captures had a spec say {builtin: "gradle", directory}: the Gradle capture of the
     build in that directory.
     */
    static final String GRADLE_BUILTIN = "gradle";

    private static final class RegenerateSerializer extends StdSerializer<Regenerate> {
        RegenerateSerializer() {
            super(Regenerate.class);
        }

        @Override
        public void serialize(Regenerate value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            gen.writeObjectField("command", value.command());
            gen.writeStringField("directory", value.directory() == null ? null : value.directory().toString());
            gen.writeObjectField("capture", value.capture());
            gen.writeEndObject();
        }
    }

    private static final class RegenerateDeserializer extends StdDeserializer<Regenerate> {
        RegenerateDeserializer() {
            super(Regenerate.class);
        }

        @Override
        public Regenerate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.readValueAsTree();
            String directory = text(node, "directory");
            JsonNode capture = node.get("capture");
            if (capture != null && !capture.isNull()) {
                return Regenerate.capture(ctxt.readTreeAsValue(capture, CaptureSpec.class));
            }
            if (GRADLE_BUILTIN.equals(text(node, "builtin"))) {
                if (directory == null) throw new IOException("a stored gradle regeneration names no directory");
                return Regenerate.capture(CaptureSpec.gradle(Path.of(directory)));
            }
            List<String> command = new ArrayList<>();
            for (JsonNode part : node.path("command")) command.add(part.asText());
            return Regenerate.command(command, directory == null ? null : Path.of(directory));
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        }
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
        out.sort(Comparator.comparing(r -> r.finishedAt() == null ? "" : r.finishedAt()));
        return out;
    }

    /* oldest first, by the run's number: run-9 before run-10 */
    private static List<Path> runFiles(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().matches("run-\\d+\\.json"))
                    .sorted(Comparator.comparingLong(ProjectStore::runNumber)).toList();
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

    /** Where a project's configuration goes when this server (maddi-project-config) makes it. */
    public Path inputConfigurationOf(String name) {
        return workDirectory(name).resolve(CompileLogCapture.INPUT_CONFIGURATION);
    }

    private Path fileOf(String name) {
        return directory.resolve(safe(name) + ".project.json");
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
