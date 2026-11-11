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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.project.config.BuildFileCheck;
import io.codelaser.maddi.project.config.CaptureSpec;
import io.codelaser.maddi.project.config.ConfiguredBy;
import io.codelaser.maddi.project.config.Directories;
import io.codelaser.maddi.project.config.ProjectConfigurator;
import io.codelaser.maddi.project.config.ProjectRecord;
import io.codelaser.maddi.project.config.Regenerate;
import io.codelaser.maddi.inspection.api.resource.BuildFiles;
import io.codelaser.maddi.inspection.resource.BuildFilesCapture;

/*
 A stored configuration against the real daemon: kept across a restart of the service, used as it is while the build
 files hash as recorded, computed again when one changes and there is a command for it, refused (or allowed) when
 there is none.
 */
public class StoredProjectTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static MaddiService.DaemonSettings settings(Path dir) {
        String install = System.getProperty("maddi.daemon.install");
        assertTrue(install != null && !install.isBlank(), "maddi.daemon.install must be set by the test task");
        Path jdk = Path.of(System.getProperty("maddi.test.jdkHome", System.getProperty("java.home")));
        return new MaddiService.DaemonSettings(Path.of(install), jdk, 0, dir.resolve("daemon.log"));
    }

    /* an inputConfiguration.json as a CLI writes it: one source set, java.base, the build files hashed now */
    private void writeInputConfiguration(Path project, Path file) throws Exception {
        BuildFiles buildFiles = BuildFilesCapture.capture(BuildFiles.COMPILE_LOG, project, BuildFiles.GRADLE_GLOBS,
                List.of());
        ObjectNode root = mapper.createObjectNode();
        root.put("workingDirectory", project.toString());
        root.set("classPathParts", mapper.valueToTree(List.of(Map.of("name", "java.base", "uri", "jmod:java.base",
                "partOfJdk", true))));
        root.set("sourceSets", mapper.valueToTree(List.of(Map.of("name", "p/main",
                "sourceDirectories", List.of("src"), "uri", project.resolve("out").toUri().toString(),
                "dependencies", List.of("java.base")))));
        root.set("buildFiles", mapper.valueToTree(buildFiles));
        mapper.writeValue(file.toFile(), root);
    }

    private static AnalysisRun finish(MaddiService service, AnalysisRun run) throws Exception {
        return service.await(run.id(), 300, null);
    }

    @Test
    public void storedCheckedRegenerated(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("p"));
        Files.createDirectories(project.resolve("src/x"));
        Files.writeString(project.resolve("src/x/A.java"), "package x; public class A { int i; void inc() { i++; } }\n");
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { java }\n");
        Path file = project.resolve("inputConfiguration.json");
        writeInputConfiguration(project, file);
        Path next = dir.resolve("next.json");
        Path store = dir.resolve("store");

        try (MaddiService service = new MaddiService(settings(dir), store)) {
            service.configure("p", new ProjectConfigurator.FromFile(file,
                    Regenerate.command(List.of("cp", next.toString(), file.toString()), project)), null);
            AnalysisRun run = finish(service, service.analyze(null, List.of(), List.of(), false));
            assertEquals(AnalysisRun.State.FINISHED, run.state(), () -> run.describe().toString());
            assertEquals("stored, build files unchanged", run.describe().get("configuration"));
        }

        // a restart: the project is still there, and still fresh, and so is its run
        try (MaddiService service = new MaddiService(settings(dir), store)) {
            Map<String, Object> p = service.projects().getFirst();
            assertEquals("p", p.get("project"));
            assertEquals("FRESH", p.get("buildFiles"), p::toString);
            AnalysisRun restored = service.resultsOf("p");
            assertEquals("run-1", restored.id());
            assertEquals(AnalysisRun.State.FINISHED, restored.state());
            assertEquals(AnalysisModel.Certainty.FINAL, restored.certainty());
            assertTrue(restored.result().elementAnnotations().stream().anyMatch(e -> "x.A.inc()".equals(e.fqn())));
            assertEquals(0, service.describe(restored).get("sourcesChangedSince"));
            Files.writeString(project.resolve("src/x/A.java"),
                    "package x; public class A { int i; void inc() { i++; } int get() { return i; } }\n");
            assertEquals(1, service.describe(restored).get("sourcesChangedSince"), "a stored result ages");

            // the build changed: the stored configuration is stale, and the command makes it again
            Files.writeString(project.resolve("build.gradle.kts"), "plugins { java; `java-library` }\n");
            assertEquals("STALE", service.projects().getFirst().get("buildFiles"));
            writeInputConfiguration(project, next);
            AnalysisRun run = finish(service, service.analyze("p", List.of(), List.of(), false));
            assertEquals(AnalysisRun.State.FINISHED, run.state(), () -> run.describe().toString());
            assertTrue(run.describe().get("configuration").toString().startsWith("regenerated, as the build changed:"
                                                                                 + " build.gradle.kts (changed)"),
                    () -> run.describe().toString());
            assertEquals("FRESH", service.projects().getFirst().get("buildFiles"), "and stored as such");
            assertEquals("run-2", run.id(), "ids go on from the stored runs");
            assertSame(restored, service.previous(run), "a stored run to compare with");
            assertTrue(RunDiff.diff(restored.result(), run.result(), null).toString().contains("x.A.get()"));
            assertTrue(run.result().elementAnnotations().stream().anyMatch(e -> "x.A.inc()".equals(e.fqn())));
        }
    }

    @Test
    public void aProjectsOwnDaemonJdk(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("p"));
        Files.createDirectories(project.resolve("src/x"));
        Files.writeString(project.resolve("src/x/A.java"), "package x; public class A { int i; void inc() { i++; } }\n");
        ProjectConfigurator.Way config = new ProjectConfigurator.FromDirectories(new Directories(project, List.of("src"), List.of(), List.of(), List.of(),
                null, 0, null, List.of("java.base"), null));
        Path jdk = Path.of(System.getProperty("maddi.test.jdkHome", System.getProperty("java.home")));
        Path store = dir.resolve("store");
        try (MaddiService service = new MaddiService(settings(dir), store)) {
            service.configure("p", config, null);
            assertNull(service.projects().getFirst().get("daemonJdk"));

            assertThrows(IllegalArgumentException.class, () -> service.setDaemonJdk("p", dir.toString()),
                    "not a JDK");
            assertEquals(jdk.toString(), service.setDaemonJdk("p", jdk.toString()).get("daemonJdk"));

            // configured again without one: the JDK the project needs stays
            service.configure("p", config, null);
            AnalysisRun run = service.await(service.analyze("p", List.of(), List.of(), false).id(), 300, null);
            assertEquals(AnalysisRun.State.FINISHED, run.state(), () -> run.describe().toString());
        }
        try (MaddiService service = new MaddiService(settings(dir), store)) {
            assertEquals(jdk.toString(), service.projects().getFirst().get("daemonJdk"), "stored");
        }
    }

    /*
     A store as the server wrote it before it kept maddi-project-config's records: a directories project without a
     configuration file (its build files hashed in the project itself), a Gradle capture as the built-in "gradle",
     and a command. All three still open, and the directories project is still checked against its own hashes.
     */
    @Test
    public void aStoreFromBefore(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("p"));
        Files.createDirectories(project.resolve("src/x"));
        Files.writeString(project.resolve("src/x/A.java"), "package x; public class A { int i; void inc() { i++; } }\n");
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        AnalysisModel.AnalyzeConfig config = ProjectConfigs.fromDirectories(new Directories(project, List.of("src"),
                List.of(), List.of(), List.of(), null, 0, null, List.of("java.base"), null));
        BuildFiles buildFiles = BuildFileCheck.hashDefault(project, List.of(project.resolve("src")));
        Path store = Files.createDirectories(dir.resolve("store"));
        ObjectNode directories = mapper.createObjectNode().put("name", "p").put("kind", "directories")
                .putNull("sourceFile").putNull("regenerate").put("configuredAt", "2026-10-01T10:00:00Z");
        directories.set("buildFiles", mapper.valueToTree(buildFiles));
        directories.set("config", mapper.valueToTree(config));
        directories.putNull("daemonJdk");
        mapper.writeValue(store.resolve("p.project.json").toFile(), directories);
        mapper.writeValue(store.resolve("g.project.json").toFile(), Map.of("name", "g", "kind", "gradle",
                "sourceFile", store.resolve("work/g/inputConfiguration.json").toString(),
                "regenerate", Map.of("command", List.of(), "directory", project.toString(), "builtin", "gradle"),
                "configuredAt", "2026-10-01T10:00:00Z"));
        mapper.writeValue(store.resolve("c.project.json").toFile(), Map.of("name", "c", "kind", "inputConfiguration",
                "sourceFile", project.resolve("inputConfiguration.json").toString(),
                "regenerate", Map.of("command", List.of("make", "config"), "directory", project.toString()),
                "configuredAt", "2026-10-01T10:00:00Z"));

        ProjectStore records = new ProjectStore(store, mapper);
        List<String> problems = new ArrayList<>();
        Map<String, ProjectRecord> byName = new HashMap<>();
        for (ProjectRecord r : records.loadAll(problems)) byName.put(r.name(), r);
        assertEquals(List.of(), problems);
        assertEquals(Set.of("p", "g", "c"), byName.keySet());
        assertEquals(ConfiguredBy.DIRECTORIES, byName.get("p").configuredBy());
        assertNull(byName.get("p").inputConfiguration());
        assertNull(byName.get("p").regenerate());
        assertEquals(ConfiguredBy.GRADLE_CAPTURE, byName.get("g").configuredBy());
        assertTrue(byName.get("g").regenerate().isCapture(), "the built-in is the Gradle capture");
        assertEquals(CaptureSpec.gradle(project), byName.get("g").regenerate().capture());
        assertEquals(Regenerate.command(List.of("make", "config"), project), byName.get("c").regenerate());
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), byName.get("c").configuredAt());

        // written back as it was read
        records.save(records.loadStored("g").orElseThrow());
        assertEquals(byName.get("g"), records.load("g").orElseThrow());

        try (MaddiService service = new MaddiService(settings(dir), store)) {
            assertEquals("FRESH", service.projects().stream().filter(m -> "p".equals(m.get("project"))).findFirst()
                    .orElseThrow().get("buildFiles"));
            AnalysisRun run = finish(service, service.analyze("p", List.of(), List.of(), false));
            assertEquals(AnalysisRun.State.FINISHED, run.state(), () -> run.describe().toString());
            assertEquals("stored, build files unchanged", run.describe().get("configuration"));

            Files.writeString(project.resolve("pom.xml"), "<project><!-- a dependency more --></project>\n");
            AnalysisRun refused = finish(service, service.analyze("p", List.of(), List.of(), false));
            assertEquals(AnalysisRun.State.FAILED, refused.state());
            assertTrue(refused.describe().get("error").toString().contains("pom.xml (changed)"),
                    () -> refused.describe().toString());
            AnalysisRun allowed = finish(service, service.analyze("p", List.of(), List.of(), true));
            assertEquals(AnalysisRun.State.FINISHED, allowed.state(), () -> allowed.describe().toString());
            assertEquals("STALE, analysed anyway: pom.xml (changed)", allowed.describe().get("configuration"));
        }
    }

    /* a stop during a regeneration kills the build that regenerates: maddi-project-config hands its process over */
    @Test
    public void stoppedWhileRegenerating(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("p"));
        Files.createDirectories(project.resolve("src/x"));
        Files.writeString(project.resolve("src/x/A.java"), "package x; public class A { }\n");
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { java }\n");
        Path file = project.resolve("inputConfiguration.json");
        writeInputConfiguration(project, file);
        Path pid = dir.resolve("pid");
        try (MaddiService service = new MaddiService(settings(dir), dir.resolve("store"))) {
            service.configure("p", new ProjectConfigurator.FromFile(file, Regenerate.command(List.of("sh", "-c",
                    "echo $$ > " + pid + "; exec sleep 300"), project)), null);
            Files.writeString(project.resolve("build.gradle.kts"), "plugins { java; `java-library` }\n");
            AnalysisRun run = service.analyze("p", List.of(), List.of(), false);
            for (int i = 0; i < 200 && !(Files.isRegularFile(pid) && !Files.readString(pid).isBlank()); i++) {
                Thread.sleep(50);
            }
            long buildPid = Long.parseLong(Files.readString(pid).strip());
            assertTrue(ProcessHandle.of(buildPid).map(ProcessHandle::isAlive).orElse(false), "regenerating");

            service.stop(run.id());
            assertTrue(run.awaitEnd(10_000));
            assertEquals(AnalysisRun.State.STOPPED, run.state(), () -> run.describe().toString());
            for (int i = 0; i < 100 && ProcessHandle.of(buildPid).map(ProcessHandle::isAlive).orElse(false); i++) {
                Thread.sleep(50);
            }
            assertFalse(ProcessHandle.of(buildPid).map(ProcessHandle::isAlive).orElse(false), "the build was killed");
        }
    }

    /*
     * the stop can come between the build's start and its hand-over (its pid already written): the build attached
     * after the stop is killed at once, not left running (gate 2026-11-11 on laser1, stoppedWhileRegenerating)
     */
    @Test
    public void buildAttachedAfterTheStop() throws Exception {
        AnalysisRun run = new AnalysisRun("run-1", "p");
        run.stop();
        assertEquals(AnalysisRun.State.STOPPED, run.state());
        Process build = new ProcessBuilder("sleep", "300").start();
        try {
            run.attach(build);
            assertTrue(build.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "the build was killed");
        } finally {
            build.destroyForcibly();
        }
    }

    @Test
    public void staleWithoutACommand(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("p"));
        Files.createDirectories(project.resolve("src/x"));
        Files.writeString(project.resolve("src/x/A.java"), "package x; public class A { }\n");
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        ProjectConfigurator.Way config = new ProjectConfigurator.FromDirectories(new Directories(project, List.of("src"), List.of(), List.of(), List.of(),
                null, 0, null, List.of("java.base"), null));

        try (MaddiService service = new MaddiService(settings(dir), dir.resolve("store"))) {
            service.configure("p", config, null);
            Files.writeString(project.resolve("pom.xml"), "<project><!-- a dependency more --></project>\n");

            AnalysisRun refused = finish(service, service.analyze("p", List.of(), List.of(), false));
            assertEquals(AnalysisRun.State.FAILED, refused.state());
            assertTrue(refused.describe().get("error").toString().contains("pom.xml (changed)"),
                    () -> refused.describe().toString());

            AnalysisRun allowed = finish(service, service.analyze("p", List.of(), List.of(), true));
            assertEquals(AnalysisRun.State.FINISHED, allowed.state(), () -> allowed.describe().toString());
            assertEquals("STALE, analysed anyway: pom.xml (changed)", allowed.describe().get("configuration"));
        }
    }
}
