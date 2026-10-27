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

package io.codelaser.maddi.gradleplugin;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proof that the <em>shaded</em> plugin jar is self-contained.
 * <p>
 * Unlike {@link TestAnalyzerPluginFunctional}, which uses {@code withPluginClasspath()} (the plugin's full
 * runtime classpath, with every analyzer module present), this test resolves the plugin <em>from a local
 * Maven repository</em> — the {@code localPluginRepo} the build publishes the shadow jar into — and applies
 * it by id and version. None of the {@code maddi-*} analyzer modules are on any classpath here; the only
 * thing that carries the analyzer is the shaded jar. If a class were missing from the shadow jar (or a
 * dependency were left in the POM but not bundled), the run would fail with {@code NoClassDefFoundError} or
 * an unresolved dependency. A green run means the jar stands alone.
 * <p>
 * Slow: round-trips through a nested Gradle build (GradleTestKit spins up its own daemon to resolve
 * and run the plugin), which can take minutes rather than seconds — excluded from the fast `test`
 * suite, runs only under `slowTest`.
 */
@Tag("slow")
public class TestAnalyzerPluginShadedJarIsolation {

    @Test
    public void shadedPluginResolvesAndRunsFromLocalRepo(@TempDir Path projectDir) throws IOException {
        String localRepo = System.getProperty("maddi.localPluginRepo");
        assertNotNull(localRepo, "system property maddi.localPluginRepo must be set by the build");
        String pluginVersion = System.getProperty("maddi.pluginVersion");
        assertNotNull(pluginVersion, "system property maddi.pluginVersion must be set by the build");

        String repoUri = Path.of(localRepo).toUri().toString();
        // Resolve the plugin ONLY from the local repo (no withPluginClasspath, no other repositories),
        // so the shaded jar is the only thing on the plugin classpath.
        Files.writeString(projectDir.resolve("settings.gradle.kts"), """
                pluginManagement {
                    repositories {
                        maven { url = uri("%s") }
                    }
                }
                rootProject.name = "iso"
                """.formatted(repoUri));
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("io.codelaser.maddi.analyzer") version "%s"
                }
                maddi {
                    jmods = "java.base"
                    analysisSteps = "modification"
                    sourcePackages = "com.example"
                }
                """.formatted(pluginVersion));
        Path pkg = Files.createDirectories(projectDir.resolve("src/main/java/com/example"));
        Files.writeString(pkg.resolve("Counter.java"), """
                package com.example;
                public class Counter {
                    private int count;
                    public void increment() { count++; }
                    public int get() { return count; }
                }
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("maddi-analyzer", "--stacktrace", "--info")
                .forwardOutput()
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":maddi-analyzer").getOutcome());
        Path results = projectDir.toRealPath().resolve("build/maddi");
        assertTrue(Files.isDirectory(results), "expected results directory " + results);
        try (var stream = Files.walk(results)) {
            assertTrue(stream.anyMatch(p -> p.getFileName().toString().endsWith(".json")),
                    "expected at least one analysis-result .json in " + results);
        }
    }
}
