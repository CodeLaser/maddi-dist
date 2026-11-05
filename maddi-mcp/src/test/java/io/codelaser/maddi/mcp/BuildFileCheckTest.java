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

import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.inspection.api.resource.BuildFiles;
import io.codelaser.maddi.inspection.resource.BuildFilesCapture;
import io.codelaser.maddi.project.config.BuildFileCheck;
import io.codelaser.maddi.project.config.Directories;

public class BuildFileCheckTest {

    private static AnalysisModel.AnalyzeConfig config(Path root) {
        return ProjectConfigs.fromDirectories(new Directories(root, List.of("a/src/main/java"), List.of(), List.of(),
                List.of(), null, 0, null, List.of("java.base"), null));
    }

    @Test
    public void whatChangedSinceTheCapture(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("settings.gradle.kts"), "include(\"a\")\n");
        Path a = Files.createDirectories(root.resolve("a"));
        Files.writeString(a.resolve("build.gradle.kts"), "plugins { java }\n");
        Path sources = Files.createDirectories(a.resolve("src/main/java"));
        Files.createDirectories(a.resolve("build"));
        Files.writeString(a.resolve("build/build.gradle.kts"), "output, never a build file\n");
        BuildFiles recorded = BuildFilesCapture.capture(BuildFiles.GRADLE_PLUGIN, root, BuildFiles.GRADLE_GLOBS,
                List.of());
        assertEquals(List.of("a/build.gradle.kts", "settings.gradle.kts"), List.copyOf(recorded.sha256().keySet()));

        assertEquals(BuildFileCheck.State.FRESH, ProjectConfigs.freshness(recorded, config(root)).state());

        Files.writeString(a.resolve("build.gradle.kts"), "plugins { java; `java-library` }\n");
        Files.writeString(a.resolve("build/build.gradle.kts"), "still output\n");
        assertEquals(List.of("a/build.gradle.kts (changed)"), ProjectConfigs.freshness(recorded, config(root)).changed());

        Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("b/build.gradle.kts"), "plugins { java }\n");
        Files.delete(root.resolve("settings.gradle.kts"));
        Files.writeString(sources.resolve("module-info.java"), "module a {}\n");
        BuildFileCheck.Freshness freshness = ProjectConfigs.freshness(recorded, config(root));
        assertEquals(BuildFileCheck.State.STALE, freshness.state());
        assertEquals(List.of("a/build.gradle.kts (changed)", "settings.gradle.kts (gone)", "b/build.gradle.kts (new)",
                "a/src/main/java/module-info.java (new)"), freshness.changed());
    }

    @Test
    public void nothingRecordedNothingChecked(@TempDir Path root) {
        assertEquals(BuildFileCheck.State.UNCHECKED, ProjectConfigs.freshness(null, config(root)).state());
    }
}
