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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.codelaser.maddi.ide.client.AnalysisModel;

public class ProjectConfigsTest {

    /* the shape the Gradle plugin's writeInputConfiguration task writes, cut down: two sets, a jar, a runtime jar */
    private static final String INPUT_CONFIGURATION = """
            {
              "workingDirectory" : "/p",
              "classPathParts" : [
                {"name":"java.base","uri":"jmod:java.base","library":true,"partOfJdk":true},
                {"name":"java.sql","uri":"jmod:java.sql","library":true,"partOfJdk":true},
                {"name":"lib.jar","uri":"file:/libs/lib.jar","library":true},
                {"name":"rt.jar","uri":"file:/libs/rt.jar","library":true,"runtimeOnly":true},
                {"name":"junit.jar","uri":"file:/libs/junit.jar","library":true,"test":true}
              ],
              "sourceSets" : [
                {"sourceEncoding":"ISO-8859-1","name":"p/main","sourceDirectories":["src/main/java"],
                 "uri":"file:///p/build/classes/java/main/","sourceRelease":21,
                 "dependencies":["java.base","java.sql","lib.jar","rt.jar"]},
                {"name":"p/test","sourceDirectories":["/p/src/test/java"],"test":true,
                 "uri":"file:///p/build/classes/java/test/",
                 "dependencies":["java.base","p/main","lib.jar","junit.jar"]}
              ],
              "alternativeJREDirectory" : null
            }
            """;

    @Test
    public void fromInputConfiguration(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("inputConfiguration.json");
        Files.writeString(file, INPUT_CONFIGURATION);
        AnalysisModel.AnalyzeConfig config = ProjectConfigs.fromInputConfiguration(file, new ObjectMapper());

        assertEquals("/p", config.workingDirectory());
        assertNull(config.sdkHome());
        assertEquals("ISO-8859-1", config.sourceEncoding());
        assertEquals(List.of("java.base", "java.sql"), config.jmods());
        assertEquals(2, config.moduleSourceSets().size());

        AnalysisModel.ModuleSourceSet main = config.moduleSourceSets().get(0);
        assertEquals("p/main", main.name());
        assertEquals(List.of("/p/src/main/java"), main.sourceDirectories(), "relative to the working directory");
        assertEquals("/p/build/classes/java/main", main.outputPath());
        assertEquals(21, main.sourceRelease());
        assertFalse(main.test());
        assertEquals(List.of(), main.dependencies());
        assertEquals(List.of("/libs/lib.jar"), main.classPath(), "no JDK modules, no runtime-only jar");

        AnalysisModel.ModuleSourceSet test = config.moduleSourceSets().get(1);
        assertTrue(test.test());
        assertEquals(List.of("p/main"), test.dependencies());
        assertEquals(List.of("/libs/lib.jar", "/libs/junit.jar"), test.classPath());
    }

    @Test
    public void partReadsDroppedSetsAsClassFiles(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("inputConfiguration.json");
        Files.writeString(file, INPUT_CONFIGURATION);
        AnalysisModel.AnalyzeConfig config = ProjectConfigs.fromInputConfiguration(file, new ObjectMapper());

        AnalysisModel.AnalyzeConfig onlyTests = ProjectConfigs.part(config, List.of("p/test"), List.of("a.b."));
        assertEquals(1, onlyTests.moduleSourceSets().size());
        AnalysisModel.ModuleSourceSet test = onlyTests.moduleSourceSets().getFirst();
        assertEquals(List.of(), test.dependencies(), "p/main is no longer a set");
        assertEquals(List.of("/libs/lib.jar", "/libs/junit.jar", "/p/build/classes/java/main"), test.classPath(),
                "p/main's classes in its place; its jar was there already");
        assertEquals(List.of("a.b."), onlyTests.restrictToPackages());

        AnalysisModel.AnalyzeConfig whole = ProjectConfigs.part(config, List.of(), List.of());
        assertEquals(config.moduleSourceSets(), whole.moduleSourceSets());
        assertThrows(IllegalArgumentException.class, () -> ProjectConfigs.part(config, List.of("nope"), List.of()));
    }

    @Test
    public void fromDirectories(@TempDir Path dir) {
        AnalysisModel.AnalyzeConfig config = ProjectConfigs.fromDirectories(dir, List.of("src"), List.of("test"),
                List.of("lib/a.jar"), List.of("lib/junit.jar"), "classes", 17, null, List.of("java.base"), null);
        assertEquals(2, config.moduleSourceSets().size());
        AnalysisModel.ModuleSourceSet main = config.moduleSourceSets().get(0);
        assertEquals(List.of(dir.resolve("src").toString()), main.sourceDirectories());
        assertEquals(dir.resolve("classes").toString(), main.outputPath());
        assertEquals(List.of(dir.resolve("lib/a.jar").toString()), main.classPath());
        AnalysisModel.ModuleSourceSet test = config.moduleSourceSets().get(1);
        assertEquals(List.of("main"), test.dependencies());
        assertEquals(List.of(dir.resolve("lib/a.jar").toString(), dir.resolve("lib/junit.jar").toString()),
                test.classPath());
        assertEquals("UTF-8", config.sourceEncoding());
    }
}
