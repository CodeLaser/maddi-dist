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

package io.codelaser.maddi.ide.plugin.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Kotlin front end override as typed in the settings (maddi-dist#6). */
public class MaddiKotlinFrontEndTest {

    @Test
    public void aTildeIsTheHomeDirectory() {
        String home = System.getProperty("user.home");
        assertEquals(Path.of(home, "maddi-kit", "k2-home"), MaddiKotlinFrontEnd.expandHome("~/maddi-kit/k2-home"));
        assertEquals(Path.of(home), MaddiKotlinFrontEnd.expandHome("~"));
        assertEquals(Path.of("/opt/k2"), MaddiKotlinFrontEnd.expandHome("/opt/k2"));
        assertEquals(Path.of("~other/k2"), MaddiKotlinFrontEnd.expandHome("~other/k2"));
    }

    @Test
    public void aDirectoryWithoutTheFrontEndJarIsAProblem(@TempDir Path dir) throws Exception {
        assertTrue(MaddiKotlinFrontEnd.problemWith(dir.resolve("missing")).contains("not a directory"));
        Files.createFile(dir.resolve("kotlin-compiler-2.4.0.jar"));
        assertTrue(MaddiKotlinFrontEnd.problemWith(dir).contains("maddi-kotlin-k2"));
        Files.createFile(dir.resolve("maddi-kotlin-k2-0.9.6.jar"));
        assertNull(MaddiKotlinFrontEnd.problemWith(dir));
    }
}
