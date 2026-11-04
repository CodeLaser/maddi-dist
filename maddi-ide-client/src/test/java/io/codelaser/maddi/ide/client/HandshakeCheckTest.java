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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The client's side of the protocol check: it goes on only with a daemon that acknowledges its own protocol
 * version, and says why otherwise, naming the daemon's install, since the usual cause is an override left pointing
 * at another release.
 */
public class HandshakeCheckTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path INSTALL = Path.of("/opt/maddi-daemon");

    @Test
    public void ownVersionIsAccepted() {
        assertDoesNotThrow(() -> MaddiDaemonProcess.checkHandshake(json(
                "{\"type\":\"handshakeAck\",\"protocolVersion\":" + MaddiDaemonProcess.PROTOCOL_VERSION + "}"), INSTALL));
    }

    @Test
    public void anOlderDaemonIsRefused() {
        // a 0.9.5 daemon acknowledges any client with its own version 1
        IOException e = assertThrows(IOException.class, () -> MaddiDaemonProcess.checkHandshake(json(
                "{\"type\":\"handshakeAck\",\"protocolVersion\":1,\"maddiVersion\":\"0.9.5\"}"), INSTALL));
        assertTrue(e.getMessage().contains("/opt/maddi-daemon (maddi 0.9.5) speaks protocol 1, this plugin speaks 2"),
                e.getMessage());
    }

    @Test
    public void aDaemonThatRefusesIsReported() {
        IOException e = assertThrows(IOException.class, () -> MaddiDaemonProcess.checkHandshake(json(
                "{\"type\":\"error\",\"kind\":\"protocol\",\"message\":\"this maddi daemon speaks protocol 3\"}"),
                INSTALL));
        assertTrue(e.getMessage().contains("refused this plugin: this maddi daemon speaks protocol 3"), e.getMessage());
    }

    private static JsonNode json(String text) throws IOException {
        return MAPPER.readTree(text);
    }
}
