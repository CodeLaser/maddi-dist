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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * End-to-end reactivation test: apply the plugin to a generated Java project and run the {@code maddi-analyzer}
 * task. It must parse (openjdk front-end, in a forked worker), run the modification analysis, and write a result
 * JSON per package to {@code build/maddi}.
 */
public class TestAnalyzerPluginFunctional {

    @Test
    public void analyzerTaskRunsAndWritesResults(@TempDir Path projectDir) throws IOException {
        writeSimpleJavaProject(projectDir);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("maddi-analyzer", "--stacktrace", "--info")
                .forwardOutput()
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":maddi-analyzer").getOutcome());
        // toRealPath() resolves the macOS /var -> /private/var symlink, so the results directory the analyzer
        // wrote (canonical path) matches what we check here
        Path results = projectDir.toRealPath().resolve("build/maddi");
        assertTrue(Files.isDirectory(results), "expected results directory " + results);
        try (var stream = Files.walk(results)) {
            assertTrue(stream.anyMatch(p -> p.getFileName().toString().endsWith(".json")),
                    "expected at least one analysis-result .json in " + results);
        }
    }

    /**
     * The written configuration declares the files Gradle read, hashed when the task ran (BuildFiles): a server
     * tells a stale capture by them. With the configuration cache: the hashes are taken at execution, not stored.
     */
    @Test
    public void writtenConfigurationDeclaresItsBuildFiles(@TempDir Path projectDir) throws IOException {
        writeSimpleJavaProject(projectDir);
        Files.writeString(projectDir.resolve("gradle.properties"), """
                org.gradle.configuration-cache=true
                org.gradle.configuration-cache.problems=fail
                """);
        Files.writeString(projectDir.resolve("src/main/java/module-info.java"), "module tp { }\n");
        GradleRunner runner = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(AnalyzerExtension.WRITE_INPUT_CONFIGURATION_TASK_NAME, "--stacktrace")
                .forwardOutput();
        runner.build();
        Path json = projectDir.resolve("build/inputConfiguration.json");
        JsonNode first = new ObjectMapper()
                .readTree(json.toFile()).get("buildFiles");
        System.out.println("buildFiles: " + first.toPrettyString());
        assertEquals("gradle-plugin", first.get("declaredBy").asText(), first.toString());
        List<String> files = new ArrayList<>();
        first.get("sha256").fieldNames().forEachRemaining(files::add);
        assertEquals(List.of("build.gradle.kts", "gradle.properties", "settings.gradle.kts",
                "src/main/java/module-info.java"), files, first.toString());

        Files.writeString(projectDir.resolve("build.gradle.kts"),
                Files.readString(projectDir.resolve("build.gradle.kts")) + "\n// edited\n");
        runner.build();
        JsonNode second = new ObjectMapper()
                .readTree(json.toFile()).get("buildFiles");
        assertEquals(first.get("sha256").get("settings.gradle.kts"), second.get("sha256").get("settings.gradle.kts"));
        Assertions.assertNotEquals(first.get("sha256").get("build.gradle.kts"),
                second.get("sha256").get("build.gradle.kts"));
    }

    /**
     * Modernization proof: with the configuration cache enabled and {@code problems=fail}, the task must store the
     * entry on the first run (a hard failure on any configuration-cache incompatibility) and reuse it on the second.
     */
    @Test
    public void configurationCacheCompatible(@TempDir Path projectDir) throws IOException {
        writeSimpleJavaProject(projectDir);
        Files.writeString(projectDir.resolve("gradle.properties"), """
                org.gradle.configuration-cache=true
                org.gradle.configuration-cache.problems=fail
                """);

        GradleRunner runner = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("maddi-analyzer", "--stacktrace")
                .forwardOutput();

        BuildResult first = runner.build();
        assertEquals(TaskOutcome.SUCCESS, first.task(":maddi-analyzer").getOutcome());

        BuildResult second = runner.build();
        assertTrue(second.getOutput().contains("Reusing configuration cache")
                   || second.getOutput().contains("Configuration cache entry reused"),
                "expected the configuration cache to be reused on the second run:\n" + second.getOutput());
        // and, unchanged inputs + present outputs => the analyzer is not re-executed (up-to-date / from cache)
        TaskOutcome secondOutcome = second.task(":maddi-analyzer").getOutcome();
        assertTrue(secondOutcome == TaskOutcome.UP_TO_DATE || secondOutcome == TaskOutcome.FROM_CACHE,
                "expected the second run to be incremental, was " + secondOutcome);
    }

    private static void writeSimpleJavaProject(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"tp\"\n");
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("io.codelaser.maddi.analyzer")
                }
                maddi {
                    jmods = "java.base"
                    analysisSteps = "modification"
                    sourcePackages = "com.example"
                }
                """);
        Path pkg = Files.createDirectories(projectDir.resolve("src/main/java/com/example"));
        Files.writeString(pkg.resolve("Counter.java"), """
                package com.example;
                public class Counter {
                    private int count;
                    public void increment() { count++; }
                    public int get() { return count; }
                }
                """);
    }
}
