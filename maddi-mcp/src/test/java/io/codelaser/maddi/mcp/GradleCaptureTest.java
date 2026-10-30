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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/*
 A Gradle build without the maddi plugin, configured by its directory alone: the configuration is made by compiling
 it (real Gradle, through this repository's wrapper), analysed, and made again when a build file changes. Slow: it
 runs Gradle three times.
 */
@Tag("slow")
public class GradleCaptureTest {

    private static MaddiService.DaemonSettings settings(Path dir) {
        String install = System.getProperty("maddi.daemon.install");
        assertTrue(install != null && !install.isBlank(), "maddi.daemon.install must be set by the test task");
        Path jdk = Path.of(System.getProperty("maddi.test.jdkHome", System.getProperty("java.home")));
        return new MaddiService.DaemonSettings(Path.of(install), jdk, 0, dir.resolve("daemon.log"));
    }

    /* settings + project a with a main and a test source set, and this repository's Gradle wrapper */
    private static Path build(Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("build-root"));
        Path repository = Path.of("..").toAbsolutePath().normalize(); // maddi-dist, the test's working dir's parent
        Files.copy(repository.resolve("gradlew"), root.resolve("gradlew"), StandardCopyOption.COPY_ATTRIBUTES);
        Path wrapper = Files.createDirectories(root.resolve("gradle/wrapper"));
        for (String f : List.of("gradle-wrapper.jar", "gradle-wrapper.properties")) {
            Files.copy(repository.resolve("gradle/wrapper").resolve(f), wrapper.resolve(f));
        }
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"demo\"\ninclude(\"a\")\n");
        Path a = Files.createDirectories(root.resolve("a"));
        Files.writeString(a.resolve("build.gradle.kts"), "plugins { java }\n");
        Path main = Files.createDirectories(a.resolve("src/main/java/x"));
        Files.writeString(main.resolve("Counter.java"), """
                package x;
                public class Counter {
                    private int count;
                    public void increment() { count++; }
                    public int get() { return count; }
                }
                """);
        Path test = Files.createDirectories(a.resolve("src/test/java/x"));
        Files.writeString(test.resolve("CounterCheck.java"), """
                package x;
                class CounterCheck {
                    static int twice(Counter c) { c.increment(); c.increment(); return c.get(); }
                }
                """);
        return root;
    }

    @Test
    public void configuredByItsDirectory(@TempDir Path dir) throws Exception {
        Path root = build(dir);
        try (MaddiService service = new MaddiService(settings(dir), dir.resolve("store"))) {
            service.configure(new ProjectStore.StoredProject("demo", ProjectStore.StoredProject.GRADLE,
                    service.inputConfigurationOf("demo").toString(),
                    ProjectStore.Regenerate.builtin(GradleCapture.BUILTIN, root.toString()), null, "now", null));
            assertTrue(service.projects().getFirst().get("configuration").toString().startsWith("not made yet"));

            AnalysisRun made = service.await(service.analyze("demo", List.of(), List.of(), false, true).id(), 600, null);
            assertEquals(AnalysisRun.State.FINISHED, made.state(), () -> made.describe().toString());
            assertEquals("made, for the first time", made.describe().get("configuration"));
            Map<String, Object> project = service.projects().getFirst();
            assertEquals("FRESH", project.get("buildFiles"), project::toString);
            assertTrue(project.get("sourceSets").toString().contains("a/main")
                       && project.get("sourceSets").toString().contains("a/test"), project::toString);

            AnalysisRun analysed = service.await(service.analyze("demo", List.of(), List.of(), false).id(), 600, null);
            assertEquals(AnalysisRun.State.FINISHED, analysed.state(), () -> analysed.describe().toString());
            assertEquals("stored, build files unchanged", analysed.describe().get("configuration"));
            assertTrue(analysed.result().elementAnnotations().stream()
                    .anyMatch(e -> "x.Counter.increment()".equals(e.fqn())
                                   && e.displayAnnotations().contains("@Modified")));

            Files.writeString(root.resolve("a/build.gradle.kts"), "plugins { java }\n// a change\n");
            AnalysisRun again = service.await(service.analyze("demo", List.of(), List.of(), false).id(), 600, null);
            assertEquals(AnalysisRun.State.FINISHED, again.state(), () -> again.describe().toString());
            assertEquals("regenerated, as a/build.gradle.kts (changed) changed", again.describe().get("configuration"));
            assertEquals("FRESH", service.projects().getFirst().get("buildFiles"));
        }
    }
}
