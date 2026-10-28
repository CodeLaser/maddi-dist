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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 end-to-end (plugin ↔ daemon): launch the real daemon, send an {@code analyzeProject} for a small
 * on-disk {@code @Container} violation, and map the JSON {@code result} back into the plugin's DTOs —
 * exactly the round-trip {@code MaddiAnalysisService} performs, but without a live IDE {@code Project}.
 * The e2immu annotations come from the daemon distribution's own {@code lib/} (its "hot class files").
 */
public class DaemonAnalyzeRoundTripTest {

    @Test
    public void analyzeContainerViolationOverSocket(@TempDir Path projectDir) throws Exception {
        String install = System.getProperty("maddi.daemon.install");
        assertTrue(install != null && !install.isBlank(), "maddi.daemon.install must be set by the test task");
        Path installDir = Path.of(install);
        // A JDK 25+ home for the daemon to run on and to read java.base from (the test JVM itself is JBR 21).
        String jdkHome = System.getProperty("maddi.test.jdkHome", System.getProperty("java.home"));

        Path pkg = Files.createDirectories(projectDir.resolve("src/x"));
        Files.writeString(pkg.resolve("Example.java"), """
                package x;
                import io.codelaser.maddi.annotation.Container;

                class Mutable {
                    private int value;
                    void set(int v) { this.value = v; }
                    int get() { return value; }
                }

                @Container
                interface HasAdd {
                    void add(Mutable m);
                }

                class BadImpl implements HasAdd {
                    @Override
                    public void add(Mutable m) { m.set(3); } // violates @Container
                }

                // one modifying slot short of @Container: only reported when warnNearMisses crosses the wire
                class Surface {
                    public int read0(StringBuilder sb) { return sb.length(); }
                    public int read1(StringBuilder sb) { return sb.length(); }
                    public int read2(StringBuilder sb) { return sb.length(); }
                    public int read3(StringBuilder sb) { return sb.length(); }
                    public int read4(StringBuilder sb) { return sb.length(); }
                    public int read5(StringBuilder sb) { return sb.length(); }
                    public int read6(StringBuilder sb) { return sb.length(); }
                    public void bang(StringBuilder sb) { sb.append('!'); }
                }
                """);

        AnalysisModel.AnalyzeConfig config = new AnalysisModel.AnalyzeConfig(
                projectDir.toAbsolutePath().toString(),
                jdkHome,
                "UTF-8",
                List.of("java.base"),
                List.of(new AnalysisModel.SourceRoot("main", "src", false)),
                List.of(), // no user classpath: the e2immu annotations come from the daemon's own classpath
                List.of(),
                false,
                true); // warnNearMisses: the client and daemon declare AnalyzeConfig separately, matched only
                       // by field name over JSON, so a skew here would silently drop the flag

        DaemonLauncher launcher = new DaemonLauncher();
        Path logFile = projectDir.resolve("daemon.log");
        try (DaemonLauncher.Handle handle = launcher.launch(installDir, Path.of(jdkHome), List.of(), 30_000, logFile);
             DaemonClient client = new DaemonClient(handle.port(), 120_000)) {

            assertEquals("handshakeAck", client.handshake(MaddiDaemonProcess.PROTOCOL_VERSION).path("type").asText());

            // partialResult frames are non-terminal, so they reach the client through this consumer, exactly
            // as an IDE sees them
            List<AnalysisModel.PartialResult> streamed = new ArrayList<>();
            JsonNode resultNode = client.analyze("req-1", config, frame -> {
                if (AnalysisModel.PARTIAL_RESULT.equals(frame.path("type").asText())) {
                    try {
                        streamed.add(client.objectMapper().treeToValue(frame, AnalysisModel.PartialResult.class));
                    } catch (Exception e) {
                        throw new AssertionError("a streamed frame did not map onto PartialResult: " + frame, e);
                    }
                } else {
                    System.out.println("status: " + frame.path("phase").asText());
                }
            });
            assertEquals("result", resultNode.path("type").asText(),
                    "expected a result, got: " + resultNode);

            AnalysisModel.Result result = client.objectMapper().treeToValue(resultNode, AnalysisModel.Result.class);
            assertEquals(0, result.parseErrorCount(), "unexpected parse errors");

            AnalysisModel.Finding violation = result.findings().stream()
                    .filter(f -> "contract-violation".equals(f.category()))
                    .findFirst().orElse(null);
            assertNotNull(violation, "expected a contract-violation over the wire");
            assertEquals("ERROR", violation.severity());
            assertNotNull(violation.uri());
            assertNotNull(violation.beginLine());
            assertFalse(violation.causes() == null || violation.causes().isEmpty(), "expected a why-chain");

            // proves warnNearMisses survived the JSON hop into the daemon's analyzer configuration
            AnalysisModel.Finding nearMiss = result.findings().stream()
                    .filter(f -> "near-miss-container".equals(f.category()))
                    .findFirst().orElse(null);
            assertNotNull(nearMiss, "expected a near-miss-container: the warnNearMisses flag must cross the wire");
            assertEquals("WARN", nearMiss.severity());

            // values were streamed while the run was in progress, and merging them gives a view the IDE could
            // have shown before the terminal frame arrived
            assertFalse(streamed.isEmpty(), "expected partialResult frames over the socket");
            AnalysisModel.Result progressive = null;
            for (AnalysisModel.PartialResult partial : streamed) {
                progressive = AnalysisModel.merge(progressive, partial);
            }
            assertNotNull(progressive);
            assertFalse(progressive.elementAnnotations().isEmpty(),
                    "the streamed frames should have carried annotations to display");

            boolean containerShown = result.elementAnnotations().stream()
                    .flatMap(e -> e.displayAnnotations().stream())
                    .anyMatch(a -> a.contains("@Container"));
            assertTrue(containerShown, "expected a @Container element annotation");

            client.shutdown();
        }

        // the daemon's stdout/stderr was teed to the log file
        assertTrue(Files.exists(logFile) && Files.size(logFile) > 0, "daemon log file should be written");
    }
}
