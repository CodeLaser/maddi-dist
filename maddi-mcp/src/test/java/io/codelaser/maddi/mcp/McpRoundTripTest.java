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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/*
 The server as an agent meets it: JSON-RPC lines over a pipe, against the real daemon distribution (installDist),
 on a two-class project. Configure, analyse, wait, ask.
 */
public class McpRoundTripTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private OutputStream toServer;
    private BufferedReader fromServer;
    private int nextId = 1;

    private static MaddiService.DaemonSettings settings(Path dir) {
        String install = System.getProperty("maddi.daemon.install");
        assertTrue(install != null && !install.isBlank(), "maddi.daemon.install must be set by the test task");
        Path jdk = Path.of(System.getProperty("maddi.test.jdkHome", System.getProperty("java.home")));
        return new MaddiService.DaemonSettings(Path.of(install), jdk, 0, dir.resolve("daemon.log"));
    }

    @Test
    public void configureAnalyzeAsk(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("src/x"));
        Files.writeString(pkg.resolve("Counter.java"), """
                package x;
                public class Counter {
                    private int count;
                    public void increment() { count++; }
                    public int get() { return count; }
                }
                """);
        Files.writeString(pkg.resolve("Point.java"), """
                package x;
                public final class Point {
                    private final int x;
                    public Point(int x) { this.x = x; }
                    public int x() { return x; }
                }
                """);

        PipedOutputStream clientOut = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(clientOut, 1 << 16);
        PipedOutputStream serverOut = new PipedOutputStream();
        PipedInputStream clientIn = new PipedInputStream(serverOut, 1 << 20);
        toServer = clientOut;
        fromServer = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));

        try (MaddiService service = new MaddiService(settings(dir))) {
            McpSyncServer server = McpMain.start(service, serverIn, serverOut);
            try {
                JsonNode init = request("initialize", Map.of("protocolVersion", "2025-06-18",
                        "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1")));
                assertEquals("maddi", init.path("result").path("serverInfo").path("name").asText(), init::toString);
                send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));

                JsonNode tools = request("tools/list", Map.of());
                List<String> names = tools.path("result").path("tools").findValuesAsText("name");
                assertTrue(names.containsAll(List.of("configure_project", "analyze", "run_status", "stop_run",
                        "verdicts", "explain", "findings", "library_annotations")), names::toString);

                JsonNode configured = call("configure_project", Map.of("workingDirectory", dir.toString(),
                        "sourceDirectories", List.of("src")));
                assertEquals("main", configured.path("sourceSets").path(0).path("name").asText(),
                        configured::toString);

                JsonNode started = call("analyze", Map.of());
                String runId = started.path("runId").asText();
                assertEquals("run-1", runId, started::toString);

                JsonNode status = call("run_status", Map.of("runId", runId, "waitSeconds", 300));
                assertEquals("FINISHED", status.path("state").asText(), status::toString);
                assertEquals("FINAL", status.path("certainty").asText(), status::toString);

                JsonNode counter = call("verdicts", Map.of("element", "x.Counter", "kind", "METHOD"));
                assertEquals("FINAL", counter.path("certainty").asText());
                String increment = annotationsOf(counter, "x.Counter.increment()");
                assertTrue(increment.contains("@Modified"), counter::toString);
                assertFalse(annotationsOf(counter, "x.Counter.get()").contains("@Modified"), counter::toString);

                JsonNode point = call("explain", Map.of("element", "x.Point"));
                assertTrue(point.path("element").path("annotations").toString().contains("@Immutable"),
                        point::toString);

                JsonNode findings = call("findings", Map.of());
                assertTrue(findings.has("total"), findings::toString);

                JsonNode library = call("library_annotations", Map.of("types", List.of("java.util.List")));
                assertTrue(library.path("elements").size() > 0, library::toString);
            } finally {
                server.closeGracefully();
            }
        }
    }

    private static String annotationsOf(JsonNode answer, String fqn) {
        for (JsonNode e : answer.path("elements")) {
            if (fqn.equals(e.path("fqn").asText())) return e.path("annotations").toString();
        }
        fail("no " + fqn + " in " + answer);
        return null;
    }

    /* a tool's answer, decoded from its text content; fails on an error answer */
    private JsonNode call(String tool, Map<String, Object> arguments) throws Exception {
        JsonNode response = request("tools/call", Map.of("name", tool, "arguments", arguments));
        JsonNode result = response.path("result");
        String text = result.path("content").path(0).path("text").asText();
        assertFalse(result.path("isError").asBoolean(false), () -> tool + ": " + text);
        return mapper.readTree(text);
    }

    private JsonNode request(String method, Map<String, Object> params) throws Exception {
        int id = nextId++;
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        while (true) {
            String line = fromServer.readLine();
            assertNotNull(line, "the server closed the stream");
            JsonNode node = mapper.readTree(line);
            if (node.path("id").asInt(-1) == id) return node; // notifications in between are skipped
        }
    }

    private void send(Map<String, Object> message) throws Exception {
        ObjectNode node = mapper.valueToTree(message);
        toServer.write((mapper.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8));
        toServer.flush();
    }
}
