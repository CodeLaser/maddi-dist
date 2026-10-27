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

package io.codelaser.maddi.ide.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-over an IDE performs for Kotlin: a real daemon process, launched by {@link MaddiDaemonProcess} with a
 * directory of K2 jars (the shape of a download), analyses a {@code .kt} file. Without the directory, the same
 * daemon analyses nothing of it and says so.
 */
public class KotlinRoundTripTest {

    private static final String BAG = """
            package k

            class Bag {
                private val items = ArrayList<String>()
                fun add(s: String) { items.add(s) }
                fun size(): Int = items.size
            }
            """;

    @Test
    public void withAndWithoutTheFrontEnd(@TempDir Path projectDir) throws Exception {
        Path installDir = Path.of(System.getProperty("maddi.daemon.install"));
        Path jdkHome = Path.of(System.getProperty("maddi.test.jdkHome", System.getProperty("java.home")));
        Path k2Home = Path.of(System.getProperty("maddi.test.k2Home"));
        Path src = projectDir.resolve("src");
        Files.createDirectories(src.resolve("k"));
        Files.writeString(src.resolve("k/Bag.kt"), BAG);

        String stdlib;
        try (Stream<Path> jars = Files.list(installDir.resolve("lib"))) {
            stdlib = jars.filter(p -> p.getFileName().toString().startsWith("kotlin-stdlib-"))
                    .findFirst().orElseThrow().toString();
        }
        AnalysisModel.AnalyzeConfig config = new AnalysisModel.AnalyzeConfig(
                projectDir.toString(), jdkHome.toString(), "UTF-8", List.of("java.base"),
                List.of(), List.of(), List.of(), false, false,
                List.of(new AnalysisModel.ModuleSourceSet("k/main", List.of(src.toString()), null, false, 0,
                        List.of(), List.of(stdlib))));

        try (MaddiDaemonProcess daemon = new MaddiDaemonProcess()) {
            daemon.ensureStarted(installDir, jdkHome, 0, k2Home, projectDir.resolve("daemon.log"));
            AnalysisModel.Result result = analyze(daemon, config);
            assertTrue(result.initializationProblems().stream().noneMatch(p -> p.contains("NOT analyzed")),
                    () -> "problems: " + result.initializationProblems());
            List<String> add = display(result, "k.Bag.add");
            assertTrue(add.contains("@Modified"), () -> "Bag.add: " + add);
            assertTrue(result.elementAnnotations().stream().anyMatch(e -> e.uri().endsWith("k/Bag.kt")));

            // the front end is a launch parameter: dropping it relaunches, and the Kotlin is reported as skipped
            daemon.ensureStarted(installDir, jdkHome, 0, null, projectDir.resolve("daemon.log"));
            AnalysisModel.Result without = analyze(daemon, config);
            assertTrue(without.initializationProblems().stream().anyMatch(p -> p.contains("NOT analyzed")),
                    () -> "problems: " + without.initializationProblems());
            assertFalse(without.elementAnnotations().stream().anyMatch(e -> e.uri().endsWith(".kt")));
        }
    }

    private static AnalysisModel.Result analyze(MaddiDaemonProcess daemon, AnalysisModel.AnalyzeConfig config)
            throws Exception {
        JsonNode node = daemon.analyze("req", config, frame -> { });
        assertEquals("result", node.path("type").asText(), () -> "expected a result, got " + node);
        return daemon.client().objectMapper().treeToValue(node, AnalysisModel.Result.class);
    }

    private static List<String> display(AnalysisModel.Result result, String fqn) {
        return result.elementAnnotations().stream().filter(e -> e.fqn() != null && e.fqn().startsWith(fqn + "("))
                .flatMap(e -> e.displayAnnotations().stream()).toList();
    }
}
