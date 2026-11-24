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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

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

            JsonNode ack = client.handshake(MaddiDaemonProcess.PROTOCOL_VERSION);
            assertEquals("handshakeAck", ack.path("type").asText());
            assertEquals(MaddiDaemonProcess.PROTOCOL_VERSION, ack.path("protocolVersion").asInt());

            JsonNode pong = client.ping();
            assertEquals("pong", pong.path("type").asText());

            client.shutdown();
        }
    }

    private static Path realInstall() {
        String install = System.getProperty("maddi.daemon.install");
        assertTrue(install != null && !install.isBlank(),
                "system property maddi.daemon.install must point at the daemon installDist dir");
        return Path.of(install);
    }

    /**
     * The real daemon, started from an install path of 200 characters with a space and a character outside ASCII.
     * Through the start scripts, a path this long put the Windows {@code .bat}'s class path line past cmd.exe's 8191
     * characters (maddi-dist#9). {@code bin/} and {@code lib/} are links to the real install.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void launchFromALongInstallPath(@TempDir Path dir) throws Exception {
        Path real = realInstall();
        Path install = dir.resolve("Program Files ë");
        while (install.toString().length() < 200) install = install.resolve("plugins_io.codelaser.maddi_0.9.7");
        install = Files.createDirectories(install.resolve("daemon"));
        Files.createSymbolicLink(install.resolve("bin"), real.resolve("bin"));
        Files.createSymbolicLink(install.resolve("lib"), real.resolve("lib"));

        try (DaemonLauncher.Handle handle = new DaemonLauncher().launch(install, null, List.of("-Xmx256m"), 30_000,
                dir.resolve("daemon.log"));
             DaemonClient client = new DaemonClient(handle.port(), 5_000)) {
            assertEquals("pong", client.ping().path("type").asText());
            client.shutdown();
        }
    }

    /**
     * The command for an install path as long as the one that broke 0.9.6 (maddi-dist#9, 140 characters), and longer:
     * the install path is not in it at all, and no argument comes near cmd.exe's 8191 characters, nor the whole
     * command near CreateProcess's 32767.
     */
    @Test
    public void commandDoesNotGrowWithTheInstallPath() throws Exception {
        DaemonLauncher.LaunchFile launchFile = DaemonLauncher.LaunchFile.read(realInstall());
        assertTrue(launchFile.jars().size() > 50, "the full lib/: " + launchFile.jars().size());
        Path jdk = Path.of("C:\\Users\\firstname.lastname\\AppData\\Local\\Programs\\Eclipse Adoptium\\jdk-25");
        List<String> command = DaemonLauncher.command(jdk, null, true, launchFile, List.of("-Xmx8192m"));

        assertTrue(command.getFirst().endsWith("java.exe"), command.getFirst());
        String classPath = command.get(command.indexOf("-cp") + 1);
        assertTrue(classPath.startsWith("lib\\") && classPath.contains(";lib\\"), classPath);
        int longest = command.stream().mapToInt(String::length).max().orElseThrow();
        int total = command.stream().mapToInt(a -> a.length() + 3).sum();
        assertTrue(longest < 4096, "longest argument: " + longest);
        assertTrue(total < 8191, "whole command: " + total);
        assertTrue(command.stream().noneMatch(a -> a.endsWith(".bat")), command.toString());
        assertEquals(List.of("io.codelaser.maddi.ide.daemon.DaemonMain", "--port", "0"),
                command.subList(command.size() - 3, command.size()));
    }

    /** Without a configured JDK: JAVA_HOME's java, else the PATH's, as the start scripts chose. */
    @Test
    public void javaWithoutAJdkHome() throws Exception {
        DaemonLauncher.LaunchFile launchFile = DaemonLauncher.LaunchFile.read(realInstall());
        assertEquals(Path.of("/opt/jdk", "bin", "java").toString(),
                DaemonLauncher.command(null, "/opt/jdk", false, launchFile, List.of()).getFirst());
        assertEquals("java", DaemonLauncher.command(null, null, false, launchFile, List.of()).getFirst());
        assertEquals("java.exe", DaemonLauncher.command(null, " ", true, launchFile, List.of()).getFirst());
    }

    /** A fake JDK whose {@code bin/java} runs {@code body}; POSIX shell only. */
    private static Path fakeJdk(Path dir, String body) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path java = bin.resolve("java");
        Files.writeString(java, "#!/bin/sh\n" + body + "\n");
        assertTrue(java.toFile().setExecutable(true));
        return dir;
    }

    /** A daemon that dies at once: the error says so, with its exit code, its last words and the log. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void exitBeforeAnnouncingIsExplained(@TempDir Path dir) throws Exception {
        Path jdk = fakeJdk(dir.resolve("jdk"), "echo 'Error: LinkageError occurred while loading main class'; exit 1");
        Path log = dir.resolve("maddi-daemon.log");
        IOException e = assertThrows(IOException.class,
                () -> new DaemonLauncher().launch(realInstall(), jdk, List.of(), 60_000, log));
        assertTrue(e.getMessage().contains("exited with code 1"), e.getMessage());
        assertTrue(e.getMessage().contains("LinkageError occurred"), e.getMessage());
        assertTrue(e.getMessage().contains(log.toString()), e.getMessage());
        assertTrue(Files.readString(log).contains("LinkageError occurred"));
    }

    /** A daemon that hangs without a word: the deadline holds; readLine used to block past it. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void silentDaemonTimesOut(@TempDir Path dir) throws Exception {
        Path jdk = fakeJdk(dir.resolve("jdk"), "sleep 30");
        long start = System.nanoTime();
        IOException e = assertThrows(IOException.class,
                () -> new DaemonLauncher().launch(realInstall(), jdk, List.of(), 1_000, null));
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertTrue(seconds < 10, "took " + seconds + " s");
        assertTrue(e.getMessage().contains("did not announce its port within 1 s"), e.getMessage());
        assertTrue(e.getMessage().contains("printed nothing"), e.getMessage());
    }
}
