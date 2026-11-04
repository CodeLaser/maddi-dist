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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * M0 smoke test: the transport answers handshake/ping and stops on shutdown, over a real socket; and the handshake
 * refuses a client of another protocol version.
 */
public class DaemonHandshakeTest {

    @Test
    public void handshakePingShutdown() throws Exception {
        AnalyzeHandler stub = (req, status) -> {
            throw new UnsupportedOperationException("not needed for this test");
        };
        DaemonMain daemon = new DaemonMain(stub);
        ServerSocket serverSocket = daemon.bind(0);
        int port = serverSocket.getLocalPort();

        Thread daemonThread = new Thread(() -> daemon.acceptLoop(serverSocket), "daemon-accept");
        daemonThread.setDaemon(true);
        daemonThread.start();

        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
            client.setSoTimeout(5_000);
            Writer out = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));

            // a client of another protocol version (a 0.9.5 plugin), or of none, is refused, and the daemon stays up
            String old = exchange(out, in, "{\"type\":\"handshake\",\"protocolVersion\":1}");
            assertTrue(old.contains("\"type\":\"error\"") && old.contains("\"kind\":\"protocol\""), old);
            assertTrue(old.contains("speaks protocol 2, the IDE plugin speaks protocol 1"), old);
            String none = exchange(out, in, "{\"type\":\"handshake\"}");
            assertTrue(none.contains("names no protocol version"), none);

            String ack = exchange(out, in, "{\"type\":\"handshake\",\"protocolVersion\":"
                                           + DaemonProtocol.PROTOCOL_VERSION + "}");
            assertTrue(ack.contains("\"type\":\"handshakeAck\""), ack);
            assertTrue(ack.contains("\"protocolVersion\":" + DaemonProtocol.PROTOCOL_VERSION), ack);
            assertTrue(ack.contains("\"daemonVersion\""), ack);

            String pong = exchange(out, in, "{\"type\":\"ping\"}");
            assertTrue(pong.contains("\"type\":\"pong\""), pong);

            String unknown = exchange(out, in, "{\"type\":\"nope\",\"requestId\":\"r1\"}");
            assertTrue(unknown.contains("\"type\":\"error\""), unknown);
            assertTrue(unknown.contains("bad-request"), unknown);

            // a handler without a model knows no library type
            String library = exchange(out, in, "{\"type\":\"libraryAnnotations\",\"requestId\":\"r2\","
                                               + "\"types\":[\"java.util.Set\"]}");
            assertTrue(library.contains("\"type\":\"libraryResult\""), library);
            assertTrue(library.contains("\"unknownTypes\":[\"java.util.Set\"]"), library);
            String noTypes = exchange(out, in, "{\"type\":\"libraryAnnotations\",\"requestId\":\"r3\"}");
            assertTrue(noTypes.contains("bad-request"), noTypes);

            String bye = exchange(out, in, "{\"type\":\"shutdown\"}");
            assertTrue(bye.contains("\"type\":\"bye\""), bye);
        }

        daemonThread.join(5_000);
        assertFalse(daemonThread.isAlive(), "daemon should stop after shutdown");
    }

    private static String exchange(Writer out, BufferedReader in, String request) throws IOException {
        out.write(request);
        out.write('\n');
        out.flush();
        String response = in.readLine();
        if (response == null) throw new IOException("connection closed, no response to: " + request);
        return response;
    }
}
