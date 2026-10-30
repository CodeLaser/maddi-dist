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

package io.codelaser.maddi.ide.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CompileLogConfigurationTest {

    @Test
    public void aGradleLogWithItsBuildFiles(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("settings.gradle.kts"), "include(\"a\")\n");
        Path a = Files.createDirectories(root.resolve("a"));
        Files.writeString(a.resolve("build.gradle.kts"), "plugins { java }\n");
        Path source = Files.createDirectories(a.resolve("src/main/java/x")).resolve("A.java");
        Files.writeString(source, "package x; public class A { }\n");
        Path log = root.resolve("compile.log");
        Files.writeString(log, """
                10:02:31.110 [DEBUG] [org.gradle.internal.operations] Build operation 'Compile Java' started
                10:02:31.114 [DEBUG] [org.gradle.api.internal.tasks.compile.NormalizingJavaCompiler] \
                Compiler arguments: -d %s -source 21 %s
                """.formatted(a.resolve("build/classes/java/main"), source));
        Path output = root.resolve("out/inputConfiguration.json");

        DaemonProtocol.InputConfigurationWritten written = CompileLogConfiguration.write(
                new DaemonProtocol.WriteInputConfiguration("r", log.toString(), root.toString(),
                        List.of("settings.gradle.kts", "**/build.gradle.kts"), List.of(), output.toString()));
        assertEquals(1, written.sourceSets());
        assertEquals(2, written.buildFiles());

        JsonNode json = new ObjectMapper().readTree(output.toFile());
        assertEquals("compile-log", json.path("buildFiles").path("declaredBy").asText(), json::toString);
        assertTrue(json.path("buildFiles").path("sha256").has("a/build.gradle.kts"), json::toString);
        assertEquals(1, json.path("sourceSets").size(), json::toString);
    }

    @Test
    public void aLogWithoutCompilationsIsRefused(@TempDir Path root) throws Exception {
        Path log = Files.writeString(root.resolve("compile.log"), "BUILD SUCCESSFUL\n");
        Exception e = assertThrows(java.io.IOException.class, () -> CompileLogConfiguration.write(
                new DaemonProtocol.WriteInputConfiguration("r", log.toString(), root.toString(), List.of(), List.of(),
                        root.resolve("x.json").toString())));
        assertTrue(e.getMessage().startsWith("no javac invocation"), e.getMessage());
    }
}
