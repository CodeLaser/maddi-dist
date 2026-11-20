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
import java.util.List;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project holding Kotlin is analyzed by the Kotlin-capable analyzer, forked from the {@code maddi-kotlin}
 * distribution this build installs ({@code :maddi-cli-kotlin:installDist}, handed over as {@code kotlinDistribution}
 * so no download happens), on the JDK this build runs on. The Kotlin file sits in {@code src/main/java}, which keeps
 * the Kotlin Gradle plugin, and its download, out of the test: javac compiles the {@code .java} files only, and the
 * analyzer reads every source file of the set.
 */
public class TestAnalyzerPluginKotlin {

    @Test
    public void aProjectWithKotlinRunsTheKotlinAnalyzer(@TempDir Path projectDir) throws IOException {
        writeMixedProject(projectDir, "");

        BuildResult result = runner(projectDir).build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":maddi-analyzer").getOutcome());
        assertTrue(result.getOutput().contains("running the maddi Kotlin analyzer"), result.getOutput());
        Path results = projectDir.toRealPath().resolve("build/maddi");
        try (var stream = Files.walk(results)) {
            List<Path> written = stream.filter(Files::isRegularFile).toList();
            assertTrue(!written.isEmpty(), "expected analysis results in " + results);
        }
    }

    @Test
    public void skipKotlinSourcesKeepsTheJavaAnalyzer(@TempDir Path projectDir) throws IOException {
        writeMixedProject(projectDir, "skipKotlinSources = true\n");

        BuildResult result = runner(projectDir).build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":maddi-analyzer").getOutcome());
        assertTrue(!result.getOutput().contains("running the maddi Kotlin analyzer"), result.getOutput());
    }

    private static GradleRunner runner(Path projectDir) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("maddi-analyzer", "--stacktrace")
                .forwardOutput();
    }

    private static void writeMixedProject(Path projectDir, String extra) throws IOException {
        String distribution = System.getProperty("maddi.test.kotlinDistribution");
        String javaHome = System.getProperty("maddi.test.kotlinJavaHome");
        assertTrue(distribution != null && Files.isDirectory(Path.of(distribution)),
                "the build passes the installed maddi-kotlin distribution: " + distribution);
        Files.writeString(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"tp\"\n");
        // configuration cache with problems=fail: the Kotlin path's injected services must not break it
        Files.writeString(projectDir.resolve("gradle.properties"), """
                org.gradle.configuration-cache=true
                org.gradle.configuration-cache.problems=fail
                """);
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("io.codelaser.maddi.analyzer")
                }
                maddi {
                    jmods = "java.base"
                    analysisSteps = "modification"
                    kotlinDistribution = "%s"
                    kotlinJavaHome = "%s"
                    %s
                }
                """.formatted(distribution.replace("\\", "/"), javaHome.replace("\\", "/"), extra));
        Path pkg = Files.createDirectories(projectDir.resolve("src/main/java/com/example"));
        Files.writeString(pkg.resolve("Counter.java"), """
                package com.example;
                public class Counter {
                    private int count;
                    public void increment() { count++; }
                    public int get() { return count; }
                }
                """);
        Files.writeString(pkg.resolve("Box.kt"), """
                package com.example
                class Box(val id: Int)
                """);
    }
}
