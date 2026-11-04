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

import io.codelaser.maddi.ide.client.AnalysisModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/*
 An incremental server against the real daemon: the first run is cold, a run after an edit is incremental and says so
 (best available, never final), a run without an edit keeps the previous result, and cold makes a certified run again.
 */
public class IncrementalRunTest {

    private static final String GET = "package x; public class A { int i; int get() { return i; } }\n";
    private static final String GET_MODIFYING = "package x; public class A { int i; int get() { return i++; } }\n";

    private static MaddiService.DaemonSettings settings(Path dir) {
        String install = System.getProperty("maddi.daemon.install");
        assertTrue(install != null && !install.isBlank(), "maddi.daemon.install must be set by the test task");
        Path jdk = Path.of(System.getProperty("maddi.test.jdkHome", System.getProperty("java.home")));
        return new MaddiService.DaemonSettings(Path.of(install), jdk, 0, dir.resolve("daemon.log"), true);
    }

    private static AnalysisRun run(MaddiService service, boolean cold) throws Exception {
        AnalysisRun run = service.await(service.analyze("p", List.of(), List.of(), false, false, cold).id(), 300,
                null);
        assertEquals(AnalysisRun.State.FINISHED, run.state(), () -> run.describe().toString());
        return run;
    }

    private static List<String> get(AnalysisRun run) {
        return run.result().elementAnnotations().stream().filter(e -> "x.A.get()".equals(e.fqn())).findFirst()
                .orElseThrow().displayAnnotations();
    }

    @Test
    public void editNoChangeCold(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("p"));
        Files.createDirectories(project.resolve("src/x"));
        Files.writeString(project.resolve("src/x/A.java"), GET);
        AnalysisModel.AnalyzeConfig config = ProjectConfigs.fromDirectories(project, List.of("src"), List.of(),
                List.of(), List.of(), null, 0, null, List.of("java.base"), null);
        try (MaddiService service = new MaddiService(settings(dir), dir.resolve("store"))) {
            service.configure(new ProjectStore.StoredProject("p", ProjectStore.StoredProject.DIRECTORIES, null, null,
                    ProjectConfigs.buildFilesOf(config), "then", config));

            AnalysisRun first = run(service, false);
            assertNull(first.incremental(), "nothing kept yet: cold");
            assertEquals(AnalysisModel.Certainty.FINAL, first.certainty());
            assertTrue(get(first).contains("@NotModified"), () -> get(first).toString());

            Files.writeString(project.resolve("src/x/A.java"), GET_MODIFYING);
            AnalysisRun edited = run(service, false);
            Map<String, Object> described = edited.describe();
            assertTrue(edited.incrementalOutcome());
            assertTrue(described.get("incremental").toString().startsWith("re-analysed after an edit"),
                    described::toString);
            assertTrue(described.get("incremental").toString().contains("seeded with"), described::toString);
            assertEquals("BEST_AVAILABLE", described.get("certainty"));
            assertTrue(described.get("certaintyNote").toString().contains("analyze with cold"), described::toString);
            assertTrue(get(edited).contains("@Modified"), () -> get(edited).toString());

            AnalysisRun again = run(service, false);
            assertEquals("no source changed since the previous run: its result stands",
                    again.describe().get("incremental"));
            assertEquals(get(edited), get(again));

            AnalysisRun cold = run(service, true);
            assertNull(cold.incremental(), () -> cold.describe().toString());
            assertFalse(cold.incrementalOutcome());
            assertEquals(AnalysisModel.Certainty.FINAL, cold.certainty(), "certified again");
            assertTrue(get(cold).contains("@Modified"), () -> get(cold).toString());

            // a stored incremental run says so after a restart
            assertEquals(described.get("incremental"), edited.snapshot().incremental());
        }
    }
}
