/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
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
