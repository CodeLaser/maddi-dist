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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/*
 Stopping the daemon: a run stopped from the IDE, an IDE closed during a run, an IDE that dies without closing.
 Against the real daemon distribution, as the round-trip tests.
 */
public class DaemonStopTest {

    private static Path installDir() {
        String install = System.getProperty("maddi.daemon.install");
        assertTrue(install != null && !install.isBlank(), "maddi.daemon.install must be set by the test task");
        return Path.of(install);
    }

    private static Path jdkHome() {
        return Path.of(System.getProperty("maddi.test.jdkHome", System.getProperty("java.home")));
    }

    private static AnalysisModel.AnalyzeConfig project(Path projectDir) throws IOException {
        Path pkg = Files.createDirectories(projectDir.resolve("src/x"));
        Files.writeString(pkg.resolve("A.java"), """
                package x;
                import java.util.ArrayList;
                import java.util.List;
                public class A {
                    private final List<String> items = new ArrayList<>();
                    void add(String s) { items.add(s); }
                }
                """);
        return new AnalysisModel.AnalyzeConfig(projectDir.toAbsolutePath().toString(), jdkHome().toString(), "UTF-8",
                List.of("java.base"), List.of(new AnalysisModel.SourceRoot("main", "src", false)), List.of(),
                List.of(), false, false);
    }

    /* Starts an analysis on another thread; the latch opens at its first status frame, when the daemon is busy. */
    private static CompletableFuture<JsonNode> analyzeInBackground(MaddiDaemonProcess daemon,
                                                                   AnalysisModel.AnalyzeConfig config,
                                                                   CountDownLatch started) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return daemon.analyze("req-1", config, frame -> started.countDown());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    @DisplayName("kill() ends a run at once; the next ensureStarted launches a fresh daemon")
    @Test
    public void stopARun(@TempDir Path projectDir) throws Exception {
        try (MaddiDaemonProcess daemon = new MaddiDaemonProcess()) {
            daemon.ensureStarted(installDir(), jdkHome(), 0, projectDir.resolve("daemon.log"));
            CountDownLatch started = new CountDownLatch(1);
            CompletableFuture<JsonNode> run = analyzeInBackground(daemon, project(projectDir), started);
            assertTrue(started.await(60, TimeUnit.SECONDS), "the run never reported progress");

            daemon.kill();
            Exception e = assertThrows(Exception.class, () -> run.get(20, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, e.getCause().getCause(), e::toString);
            assertFalse(daemon.isAlive());

            daemon.ensureStarted(installDir(), jdkHome(), 0, projectDir.resolve("daemon.log"));
            assertTrue(daemon.isAlive());
            // a fresh daemon has no model yet: every library type is unknown
            JsonNode library = daemon.libraryAnnotations("lib-1", List.of("java.util.Set"));
            assertEquals(AnalysisModel.LIBRARY_RESULT, library.path("type").asText(), library::toString);
            assertEquals("java.util.Set", library.path("unknownTypes").path(0).asText(), library::toString);
        }
    }

    @DisplayName("close() during a run does not wait for the run: the daemon is gone at once")
    @Test
    public void closeDuringARun(@TempDir Path projectDir) throws Exception {
        MaddiDaemonProcess daemon = new MaddiDaemonProcess();
        daemon.ensureStarted(installDir(), jdkHome(), 0, projectDir.resolve("daemon.log"));
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<JsonNode> run = analyzeInBackground(daemon, project(projectDir), started);
        assertTrue(started.await(60, TimeUnit.SECONDS), "the run never reported progress");

        CompletableFuture.runAsync(daemon::close).get(20, TimeUnit.SECONDS);
        assertFalse(daemon.isAlive());
        assertThrows(Exception.class, () -> run.get(20, TimeUnit.SECONDS));
    }

    @DisplayName("the daemon exits when the process that launched it does, also one that never closes it")
    @Test
    public void exitWithParent(@TempDir Path projectDir) throws Exception {
        // stands in for an IDE that is killed: it never gets to stop its daemon
        Process sleeper = new ProcessBuilder("sleep", "60").start();
        try (DaemonLauncher.Handle handle = new DaemonLauncher().launch(installDir(), jdkHome(), List.of(),
                Map.of(DaemonLauncher.ENV_PARENT_PID, Long.toString(sleeper.pid())), 30_000,
                projectDir.resolve("daemon.log"))) {
            assertTrue(handle.process().isAlive());
            sleeper.destroyForcibly().waitFor();
            assertTrue(handle.process().waitFor(20, TimeUnit.SECONDS), "the daemon outlived its parent");
        } finally {
            sleeper.destroyForcibly();
        }
    }
}
