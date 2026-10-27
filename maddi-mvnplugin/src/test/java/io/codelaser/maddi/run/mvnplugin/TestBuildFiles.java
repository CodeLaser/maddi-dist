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

package io.codelaser.maddi.run.mvnplugin;

import io.codelaser.maddi.inspection.api.resource.BuildFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The build files a Maven-written input configuration declares, hashed at capture time. */
public class TestBuildFiles {

    private static Path write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    @Test
    public void pomsMvnAndModuleInfo(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("reactor");
        write(root, "pom.xml", "<project/>");
        write(root, "core/pom.xml", "<project/>");
        write(root, "core/src/main/java/module-info.java", "module core { }");
        write(root, ".mvn/maven.config", "-T 4");
        write(root, "core/target/classes/pom.xml", "output, not a build file");
        Path parent = write(tmp, "parent/pom.xml", "<project/>");

        io.codelaser.maddi.inspection.api.resource.InputConfiguration configuration =
                new io.codelaser.maddi.inspection.resource.InputConfigurationImpl.Builder()
                        .setWorkingDirectory(root.toString()).addSource("core/main", "core/src/main/java").build();
        BuildFiles first = WriteInputConfigurationMojo.buildFiles(root, List.of(parent), configuration);
        assertEquals(BuildFiles.MAVEN_PLUGIN, first.declaredBy());
        assertEquals(List.of("../parent/pom.xml", ".mvn/maven.config", "core/pom.xml",
                "core/src/main/java/module-info.java", "pom.xml"), List.copyOf(first.sha256().keySet()));
        assertEquals(List.of("**/pom.xml", ".mvn/**", "core/src/main/java/module-info.java"), first.globs());
        assertEquals(first.sha256(), WriteInputConfigurationMojo.buildFiles(root, List.of(parent), configuration).sha256());

        write(root, "core/pom.xml", "<project><artifactId>core</artifactId></project>");
        BuildFiles changed = WriteInputConfigurationMojo.buildFiles(root, List.of(parent), configuration);
        assertNotEquals(first.sha256().get("core/pom.xml"), changed.sha256().get("core/pom.xml"));
        assertEquals(first.sha256().get("pom.xml"), changed.sha256().get("pom.xml"));
    }
}
