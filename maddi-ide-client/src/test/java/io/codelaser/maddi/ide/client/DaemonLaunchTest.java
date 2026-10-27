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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M0 end-to-end (plugin side): launch the real daemon distribution and complete a handshake,
 * exactly as {@code MaddiDaemonProcess} will from inside the IDE. The install path is provided by
 * the Gradle {@code test} task (which depends on {@code :maddi-ide-daemon:installDist}).
 */
public class DaemonLaunchTest {

    @Test
    public void launchAndHandshake() throws Exception {
        String install = System.getProperty("maddi.daemon.install");
        assertTrue(install != null && !install.isBlank(),
                "system property maddi.daemon.install must point at the daemon installDist dir");
        Path installDir = Path.of(install);

        DaemonLauncher launcher = new DaemonLauncher();
        // jdkHome=null: use the launcher's default java (the Gradle daemon JVM is 25+ here)
        try (DaemonLauncher.Handle handle = launcher.launch(installDir, null, List.of(), 30_000, null);
             DaemonClient client = new DaemonClient(handle.port(), 5_000)) {

            JsonNode ack = client.handshake(1);
            assertEquals("handshakeAck", ack.path("type").asText());
            assertEquals(1, ack.path("protocolVersion").asInt());

            JsonNode pong = client.ping();
            assertEquals("pong", pong.path("type").asText());

            client.shutdown();
        }
    }

    /** A fake install whose launcher runs {@code body}; POSIX shell only. */
    private static Path fakeInstall(Path dir, String body) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path script = bin.resolve("maddi-ide-daemon");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        assertTrue(script.toFile().setExecutable(true));
        return dir;
    }

    /** A daemon that dies at once: the error says so, with its exit code, its last words and the log. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void exitBeforeAnnouncingIsExplained(@TempDir Path dir) throws Exception {
        Path install = fakeInstall(dir.resolve("install"), "echo 'Error: LinkageError occurred while loading main class'; exit 1");
        Path log = dir.resolve("maddi-daemon.log");
        IOException e = assertThrows(IOException.class,
                () -> new DaemonLauncher().launch(install, null, List.of(), 60_000, log));
        assertTrue(e.getMessage().contains("exited with code 1"), e.getMessage());
        assertTrue(e.getMessage().contains("LinkageError occurred"), e.getMessage());
        assertTrue(e.getMessage().contains(log.toString()), e.getMessage());
        assertTrue(Files.readString(log).contains("LinkageError occurred"));
    }

    /** A daemon that hangs without a word: the deadline holds; readLine used to block past it. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void silentDaemonTimesOut(@TempDir Path dir) throws Exception {
        Path install = fakeInstall(dir.resolve("install"), "sleep 30");
        long start = System.nanoTime();
        IOException e = assertThrows(IOException.class,
                () -> new DaemonLauncher().launch(install, null, List.of(), 1_000, null));
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertTrue(seconds < 10, "took " + seconds + " s");
        assertTrue(e.getMessage().contains("did not announce its port within 1 s"), e.getMessage());
        assertTrue(e.getMessage().contains("printed nothing"), e.getMessage());
    }
}
