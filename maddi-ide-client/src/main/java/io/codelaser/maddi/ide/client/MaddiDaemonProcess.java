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

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Owns one long-lived maddi daemon process for the IDE: launches it lazily, keeps it warm across
 * requests, and re-launches if it dies. Thread-safe; a single request runs at a time.
 */
public final class MaddiDaemonProcess implements Closeable {
    /**
     * The daemon protocol this client speaks; the daemon's {@code DaemonProtocol.PROTOCOL_VERSION} documents the
     * versions. Both sides check it at the handshake.
     */
    public static final int PROTOCOL_VERSION = 2;

    /** What a running daemon was launched WITH; a request that differs needs a new process, not this one. */
    private record Launch(Path installDir, Path jdkHome, int xmxMb, Path kotlinFrontEndHome, List<String> jvmArgs) {
    }

    private final DaemonLauncher launcher = new DaemonLauncher();
    // volatile: kill() reads it without the monitor, which a running analysis holds
    private volatile DaemonLauncher.Handle handle;
    private volatile boolean busy;
    private DaemonClient client;
    private String buildStamp = "unknown";
    private String maddiVersion = "unknown";
    private Launch launched;

    /**
     * Ensure a warm daemon is running on {@code jdkHome}; (re)launch + handshake if needed.
     * <p>
     * ⛔ <b>A LIVE PROCESS IS NOT NECESSARILY THE REQUESTED ONE.</b> This used to return early on nothing but
     * "the process is alive", so changing the daemon install directory (or the JDK, or the heap) in the
     * settings had no effect until that process happened to die — the setting said one thing and the running
     * daemon was another, silently. Since the install directory is exactly how one iterates on the daemon
     * without rebuilding and reinstalling the whole plugin, that made the fast loop unusable. Relaunching on a
     * changed launch parameter is what makes the setting mean something.
     */
    public synchronized void ensureStarted(Path installDir, Path jdkHome, int xmxMb, Path logFile)
            throws IOException {
        ensureStarted(installDir, jdkHome, xmxMb, null, logFile);
    }

    /**
     * As above, for a daemon that can also read Kotlin.
     *
     * @param kotlinFrontEndHome the directory of K2 jars ({@link KotlinFrontEndInstaller}); {@code null} for none,
     *                           in which case the daemon analyses the Java half of a mixed project and says so.
     *                           A change relaunches the daemon: the front end is installed once per JVM.
     */
    public synchronized void ensureStarted(Path installDir, Path jdkHome, int xmxMb, Path kotlinFrontEndHome,
                                           Path logFile) throws IOException {
        ensureStarted(installDir, jdkHome, xmxMb, kotlinFrontEndHome, List.of(), logFile);
    }

    /**
     * As above, with JVM arguments of the caller's own after the heap's, such as {@code -Dmaddi.daemon.warm=true}.
     * A change relaunches the daemon, as any other launch parameter does.
     */
    public synchronized void ensureStarted(Path installDir, Path jdkHome, int xmxMb, Path kotlinFrontEndHome,
                                           List<String> extraJvmArgs, Path logFile) throws IOException {
        Launch wanted = new Launch(installDir, jdkHome, xmxMb, kotlinFrontEndHome, List.copyOf(extraJvmArgs));
        if (handle != null && handle.process().isAlive() && client != null && wanted.equals(launched)) return;
        closeQuietly();
        List<String> jvmArgs = new ArrayList<>();
        if (xmxMb > 0) {
            jvmArgs.add("-Xmx" + xmxMb + "m");
            jvmArgs.add("-XX:+UseG1GC");
        }
        jvmArgs.addAll(extraJvmArgs);
        java.util.Map<String, String> environment = kotlinFrontEndHome == null ? java.util.Map.of()
                : java.util.Map.of(KotlinFrontEndInstaller.ENV_K2_HOME, kotlinFrontEndHome.toAbsolutePath().toString());
        handle = launcher.launch(installDir, jdkHome, jvmArgs, environment, 60_000, logFile);
        client = new DaemonClient(handle.port(), 600_000); // heartbeats keep long analyses alive
        JsonNode ack = client.handshake(PROTOCOL_VERSION);
        try {
            checkHandshake(ack, installDir);
        } catch (IOException e) {
            closeQuietly(); // a daemon this client cannot talk to is of no use warm
            throw e;
        }
        buildStamp = ack.path("buildStamp").asText("unknown");
        maddiVersion = ack.path("maddiVersion").asText("unknown");
        launched = wanted;
    }

    /**
     * Whether the daemon's answer to the handshake lets this client go on. A daemon from 0.9.6 on refuses a client
     * of another protocol version with an error; an older one acknowledges anything with its own version 1. Either
     * way the message names the daemon's install and what to do, since the usual cause is a daemon install override
     * left pointing at another release.
     */
    static void checkHandshake(JsonNode ack, Path installDir) throws IOException {
        String type = ack.path("type").asText("");
        if ("error".equals(type)) {
            throw new IOException("the maddi daemon at " + installDir + " refused this plugin: "
                                  + ack.path("message").asText(ack.toString()));
        }
        if (!"handshakeAck".equals(type)) throw new IOException("daemon handshake failed: " + ack);
        int spoken = ack.path("protocolVersion").asInt(-1);
        if (spoken != PROTOCOL_VERSION) {
            throw new IOException("the maddi daemon at " + installDir + " (maddi "
                                  + ack.path("maddiVersion").asText("unknown") + ") speaks protocol " + spoken
                                  + ", this plugin speaks " + PROTOCOL_VERSION
                                  + ": use the daemon of the plugin's own release (clear the daemon install override)"
                                  + " or a plugin of the daemon's");
        }
    }

    /**
     * The source state the running daemon was built from, or {@code "unknown"} against a daemon predating the
     * stamp. Worth logging next to the install directory on every start: which build the IDE is running is
     * otherwise unobservable, and a stale bundled daemon presents as a regression in the analyzer.
     */
    public synchronized String buildStamp() {
        return buildStamp;
    }

    /** The maddi release the running daemon belongs to, from its handshake; {@code "unknown"} if it did not say. */
    public synchronized String maddiVersion() {
        return maddiVersion;
    }

    /** Send an analyze request; blocks until the daemon returns {@code result} or {@code error}. */
    public synchronized JsonNode analyze(String requestId, Object config, Consumer<JsonNode> onStatus)
            throws IOException {
        return analyze(requestId, config, false, onStatus);
    }

    /** As above; {@code cold}: from scratch, whatever a warm daemon kept of the last run. */
    public synchronized JsonNode analyze(String requestId, Object config, boolean cold, Consumer<JsonNode> onStatus)
            throws IOException {
        if (client == null) throw new IOException("daemon not started");
        busy = true;
        try {
            return client.analyze(requestId, config, cold, onStatus);
        } finally {
            busy = false;
        }
    }

    /**
     * Stop the daemon now, whatever it is doing: how a run is stopped. The analysis waiting for its result then fails
     * with "daemon closed the connection", and the next {@code ensureStarted} launches a fresh daemon; nothing is lost
     * that a new run does not rebuild, since every run builds its own model.
     * <p>
     * Not synchronized, deliberately: a running analysis holds the monitor until its result arrives, which is exactly
     * what is not to be waited for. The process's exit is, briefly: a forcible destroy only asks the OS, and on a
     * loaded machine the daemon could otherwise still be alive when this returns.
     */
    public void kill() {
        DaemonLauncher.Handle h = handle;
        if (h == null) return;
        List<ProcessHandle> descendants = h.process().descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        h.process().destroyForcibly();
        try {
            h.process().waitFor(KILL_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            for (ProcessHandle descendant : descendants) {
                descendant.onExit().get(KILL_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            // destroyed, but not yet reaped: nothing more this side can do
        }
    }

    private static final long KILL_WAIT_SECONDS = 10;

    /**
     * What the last analysis knows of these library types: a {@code libraryResult} frame, or an {@code error} one
     * (an older daemon knows no such request). Waits for a running analysis, as every request does.
     */
    public synchronized JsonNode libraryAnnotations(String requestId, List<String> types) throws IOException {
        if (client == null) throw new IOException("daemon not started");
        return client.requestUntil(java.util.Map.of("type", AnalysisModel.LIBRARY_ANNOTATIONS, "requestId", requestId,
                "types", types), AnalysisModel.LIBRARY_RESULT, null);
    }

    /**
     * These project types in Kotlin, from the last analysis: a {@code translateResult} frame, or an {@code error} one
     * (an older daemon knows no such request). {@code together}: compiled as one Kotlin code base (a module); else
     * each file on its own, the rest staying Java.
     */
    public synchronized JsonNode translate(String requestId, List<String> types, boolean together)
            throws IOException {
        if (client == null) throw new IOException("daemon not started");
        return client.requestUntil(java.util.Map.of("type", AnalysisModel.TRANSLATE, "requestId", requestId,
                "types", types, "together", together), AnalysisModel.TRANSLATE_RESULT, null);
    }

    /**
     * A compile log turned into an {@code inputConfiguration.json} at {@code output}, the build files matched by
     * {@code buildFileGlobs} (relative to {@code buildRoot}) hashed into it: an {@code inputConfigurationWritten}
     * frame, or an {@code error} one (an older daemon knows no such request). Needs no analysis.
     */
    public synchronized JsonNode writeInputConfiguration(String requestId, Path compileLog, Path buildRoot,
                                                         List<String> buildFileGlobs, List<String> extraJmods,
                                                         Path output) throws IOException {
        if (client == null) throw new IOException("daemon not started");
        return client.requestUntil(java.util.Map.of("type", AnalysisModel.WRITE_INPUT_CONFIGURATION,
                "requestId", requestId, "compileLog", compileLog.toString(), "buildRoot", buildRoot.toString(),
                "buildFileGlobs", buildFileGlobs, "extraJmods", extraJmods, "output", output.toString()),
                AnalysisModel.INPUT_CONFIGURATION_WRITTEN, null);
    }

    public synchronized DaemonClient client() {
        return client;
    }

    public synchronized boolean isAlive() {
        return handle != null && handle.process().isAlive();
    }

    /**
     * Stop the daemon: politely when it is idle, by {@link #kill()} when it is analyzing. ⛔ This used to wait for the
     * monitor, which a running analysis holds until it ends: closing the IDE during a run left the daemon running on.
     */
    @Override
    public void close() {
        if (busy) kill();
        synchronized (this) {
            closeQuietly();
        }
    }

    /**
     * Stop the daemon, so the next {@code ensureStarted} launches a fresh one. The point of exposing it: after
     * an {@code installDist} the on-disk daemon has changed but nothing about the RUNNING one has, and it would
     * otherwise be kept warm across the very rebuild being tested.
     */
    public synchronized void restart() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (client != null) {
            try {
                client.shutdown();
            } catch (IOException ignored) {
                // daemon may already be gone
            }
            try {
                client.close();
            } catch (IOException ignored) {
                // ignore
            }
            client = null;
        }
        if (handle != null) {
            handle.close();
            handle = null;
        }
        launched = null;
        buildStamp = "unknown";
    }
}
