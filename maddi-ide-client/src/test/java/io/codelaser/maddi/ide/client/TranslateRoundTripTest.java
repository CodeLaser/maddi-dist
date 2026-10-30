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

package io.codelaser.maddi.ide.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/*
 The translate request over the socket, against the real daemon distribution: the client's records read what the
 daemon's write (they are declared separately, matched by field name only).
 */
public class TranslateRoundTripTest {

    @DisplayName("analyze, then translate: the Kotlin and the translator's report reach the client")
    @Test
    public void translate(@TempDir Path projectDir) throws Exception {
        Path install = Path.of(System.getProperty("maddi.daemon.install"));
        Path jdk = Path.of(System.getProperty("maddi.test.jdkHome", System.getProperty("java.home")));
        Path pkg = Files.createDirectories(projectDir.resolve("src/x"));
        Files.writeString(pkg.resolve("Names.java"), """
                package x;
                import java.util.ArrayList;
                import java.util.List;
                public class Names {
                    private final List<String> names = new ArrayList<>();
                    public int count() { return names.size(); }
                }
                """);
        AnalysisModel.AnalyzeConfig config = new AnalysisModel.AnalyzeConfig(projectDir.toString(), jdk.toString(),
                "UTF-8", List.of("java.base"), List.of(new AnalysisModel.SourceRoot("main", "src", false)), List.of(),
                List.of(), false, false);
        try (MaddiDaemonProcess daemon = new MaddiDaemonProcess()) {
            daemon.ensureStarted(install, jdk, 0, projectDir.resolve("daemon.log"));
            assertEquals("result", daemon.analyze("req-1", config, frame -> { }).path("type").asText());
            JsonNode node = daemon.translate("tr-1", List.of("x.Names"), true);
            assertEquals(AnalysisModel.TRANSLATE_RESULT, node.path("type").asText(), node::toString);
            AnalysisModel.TranslateResult result =
                    daemon.client().objectMapper().treeToValue(node, AnalysisModel.TranslateResult.class);
            assertEquals("tr-1", result.requestId());
            assertEquals(1, result.files().size(), result::toString);
            AnalysisModel.TranslatedFile file = result.files().getFirst();
            assertEquals("x.Names", file.type());
            assertEquals("x/Names.kt", file.path());
            assertTrue(file.source().contains("class Names"), file.source());
            assertNotNull(result.messages());
            assertEquals(List.of(), result.unknownTypes());
        }
    }
}
