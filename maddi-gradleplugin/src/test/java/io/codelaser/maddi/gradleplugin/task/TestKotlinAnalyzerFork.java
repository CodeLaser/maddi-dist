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

package io.codelaser.maddi.gradleplugin.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.run.config.Configuration;
import io.codelaser.maddi.run.config.GeneralConfiguration;
import io.codelaser.maddi.run.config.util.JsonStreaming;

/** Which analyzer the task forks, and how it finds the Kotlin one; the fork itself is TestAnalyzerPluginKotlin's. */
public class TestKotlinAnalyzerFork {

    @Test
    public void aKotlinFileRoutesToTheKotlinAnalyzer(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java/p"));
        Files.writeString(tmp.resolve("src/main/java/p/A.java"), "package p; class A {}");
        assertFalse(KotlinAnalyzerFork.route(roundTrip(configuration(tmp, false))).kotlin());

        Files.createDirectories(tmp.resolve("src/main/kotlin/p"));
        Files.writeString(tmp.resolve("src/main/kotlin/p/B.kt"), "package p\nclass B");
        KotlinAnalyzerFork.Route route = KotlinAnalyzerFork.route(roundTrip(configuration(tmp, false)));
        assertTrue(route.kotlin());
        assertEquals(1, route.kotlinSourceDirectories());

        assertFalse(KotlinAnalyzerFork.route(roundTrip(configuration(tmp, true))).kotlin(),
                "skipKotlinSources keeps the Java-only analyzer");
    }

    @Test
    public void relativeSourceDirectoriesResolveAgainstTheWorkingDirectory(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/kotlin/p"));
        Files.writeString(tmp.resolve("src/main/kotlin/p/B.kt"), "package p\nclass B");
        SourceSet kotlin = new SourceSetImpl.Builder().setName("main")
                .setSourceDirectories(List.of(Path.of("src/main/kotlin"))).setUri(tmp.toUri()).build();
        Configuration configuration = new Configuration.Builder()
                .setInputConfiguration(new InputConfigurationImpl.Builder().setWorkingDirectory(tmp.toString())
                        .addSourceSets(kotlin).build())
                .build();
        assertTrue(KotlinAnalyzerFork.route(roundTrip(configuration)).kotlin());
    }

    @Test
    public void aDistributionDirectoryIsUsedAsIs(@TempDir Path tmp) throws Exception {
        Path dist = tmp.resolve("maddi-kotlin-1.2.3");
        Files.createDirectories(dist.resolve("lib"));
        Files.createDirectories(dist.resolve("lib-k2"));
        Files.writeString(dist.resolve("lib/maddi-run-kotlin-1.2.3.jar"), "");
        Files.writeString(dist.resolve("lib/maddi-run-analysis-1.2.3.jar"), "");
        Files.writeString(dist.resolve("lib-k2/maddi-kotlin-k2-1.2.3.jar"), "");

        Path home = KotlinAnalyzerFork.distribution(dist.toString(), tmp.resolve("cache"), "9.9.9");
        assertEquals(dist.toAbsolutePath(), home);
        assertEquals(List.of("maddi-run-analysis-1.2.3.jar", "maddi-run-kotlin-1.2.3.jar"),
                KotlinAnalyzerFork.classPath(home).stream().map(f -> f.getName()).toList());
        List<String> jvmArgs = KotlinAnalyzerFork.jvmArgs(List.of("--add-exports", "x"), home, " -Xmx8g  -Da=b ");
        assertEquals(List.of("--add-exports", "x", "-Dmaddi.k2.home=" + home.resolve("lib-k2"), "-Xmx8g", "-Da=b"),
                jvmArgs);
        assertFalse(Files.exists(tmp.resolve("cache")), "a directory is not copied into the cache");
    }

    @Test
    public void aDirectoryThatIsNoDistributionIsRefused(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("empty"));
        GradleException e = assertThrows(GradleException.class,
                () -> KotlinAnalyzerFork.distribution(tmp.resolve("empty").toString(), tmp.resolve("cache"), "1"));
        assertTrue(e.getMessage().contains("needs lib/ and lib-k2/"), e.getMessage());
        e = assertThrows(GradleException.class,
                () -> KotlinAnalyzerFork.distribution(tmp.resolve("absent").toString(), tmp.resolve("cache"), "1"));
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
    }

    private static Configuration configuration(Path tmp, boolean skipKotlin) {
        InputConfigurationImpl.Builder input = new InputConfigurationImpl.Builder();
        for (String dir : List.of("src/main/java", "src/main/kotlin")) {
            Path path = tmp.resolve(dir);
            input.addSourceSets(new SourceSetImpl.Builder().setName(dir).setSourceDirectories(List.of(path))
                    .setUri(path.toUri()).build());
        }
        return new Configuration.Builder()
                .setGeneralConfiguration(new GeneralConfiguration.Builder().setSkipKotlinSources(skipKotlin).build())
                .setInputConfiguration(input.build())
                .build();
    }

    /** Through JSON, as the task receives it. */
    private static Configuration roundTrip(Configuration configuration) throws Exception {
        return KotlinAnalyzerFork.read(JsonStreaming.objectMapper().writeValueAsString(configuration));
    }
}
