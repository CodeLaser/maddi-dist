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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.inspection.api.resource.BuildFiles;
import io.codelaser.maddi.inspection.resource.BuildFilesCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
            service.configure(new ProjectStore.StoredProject("p", ProjectStore.StoredProject.INPUT_CONFIGURATION,
                    file.toString(), new ProjectStore.Regenerate(List.of("cp", next.toString(), file.toString()),
                    project.toString()), ProjectConfigs.buildFilesOf(file, mapper), "then",
                    ProjectConfigs.fromInputConfiguration(file, mapper)));
            AnalysisRun run = finish(service, service.analyze(null, List.of(), List.of(), false));
            assertEquals(AnalysisRun.State.FINISHED, run.state(), () -> run.describe().toString());
            assertEquals("stored, build files unchanged", run.describe().get("configuration"));
        }

        // a restart: the project is still there, and still fresh
        try (MaddiService service = new MaddiService(settings(dir), store)) {
            Map<String, Object> p = service.projects().getFirst();
            assertEquals("p", p.get("project"));
            assertEquals("FRESH", p.get("buildFiles"), p::toString);

            // the build changed: the stored configuration is stale, and the command makes it again
            Files.writeString(project.resolve("build.gradle.kts"), "plugins { java; `java-library` }\n");
            assertEquals("STALE", service.projects().getFirst().get("buildFiles"));
            writeInputConfiguration(project, next);
            AnalysisRun run = finish(service, service.analyze("p", List.of(), List.of(), false));
            assertEquals(AnalysisRun.State.FINISHED, run.state(), () -> run.describe().toString());
            assertTrue(run.describe().get("configuration").toString().startsWith("regenerated, as build.gradle.kts"
                                                                                 + " (changed) changed"),
                    () -> run.describe().toString());
            assertEquals("FRESH", service.projects().getFirst().get("buildFiles"), "and stored as such");
            assertTrue(run.result().elementAnnotations().stream().anyMatch(e -> "x.A.inc()".equals(e.fqn())));
        }
    }

    @Test
    public void staleWithoutACommand(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("p"));
        Files.createDirectories(project.resolve("src/x"));
        Files.writeString(project.resolve("src/x/A.java"), "package x; public class A { }\n");
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        AnalysisModel.AnalyzeConfig config = ProjectConfigs.fromDirectories(project, List.of("src"), List.of(),
                List.of(), List.of(), null, 0, null, List.of("java.base"), null);

        try (MaddiService service = new MaddiService(settings(dir), dir.resolve("store"))) {
            service.configure(new ProjectStore.StoredProject("p", ProjectStore.StoredProject.DIRECTORIES, null, null,
                    BuildFileCheck.capture(config), "then", config));
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
