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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Launches the bundled maddi daemon distribution (the {@code installDist} layout: {@code bin/} + {@code lib/*.jar})
 * on a chosen JDK (25+), and reads back the ephemeral port it prints. Plain JDK only — no IntelliJ types — so it is
 * unit-testable against a real install.
 * <p>
 * It starts {@code java} itself, from the distribution's {@code bin/maddi-ide-daemon.launch}, and not through the
 * start scripts in {@code bin/} (maddi-dist#9): the Windows {@code .bat} spells out every jar in one line, which
 * {@code cmd.exe} refuses past 8191 characters, so whether the daemon started depended on how long the install path
 * was; and Node, for the VS Code extension, does not spawn a {@code .bat} at all without a shell.
 * <p>
 * The class path names the jars relative to the install directory, which is the daemon's working directory:
 * {@code lib/<jar>}, about 2,400 characters for the 59 jars of 0.9.7, whatever the install path. Not absolute paths in
 * an {@code @argfile}: the launcher reads an argfile in the platform's encoding, the ANSI code page on Windows, so a
 * user name outside it would break the class path. Each JVM argument is an argument of its own.
 */
public final class DaemonLauncher {
    /**
     * The environment variable naming the pid of the process that launched the daemon (the IDE). The daemon exits when
     * that process does, also when it is killed or crashes and never gets to stop the daemon itself. Mirrors
     * {@code DaemonMain.ENV_PARENT_PID} in maddi-ide-daemon.
     */
    public static final String ENV_PARENT_PID = "MADDI_PARENT_PID";
    private static final Pattern PORT_LINE = Pattern.compile("DAEMON_PORT=(\\d+)");
    private static final Pattern PID_LINE = Pattern.compile("DAEMON_PID=(\\d+)");
    private static final int TAIL_LINES = 30;
    static final String LAUNCH_FILE = "maddi-ide-daemon.launch";
    // end of the daemon's output; compared by identity
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String END = new String("<end of output>");

    /** The distribution's launch description: main class, JVM arguments, and the jars of {@code lib/} in order. */
    record LaunchFile(String mainClass, List<String> jvmArgs, List<String> jars) {
        static LaunchFile read(Path installDir) throws IOException {
            Path file = installDir.resolve("bin").resolve(LAUNCH_FILE);
            if (!Files.isRegularFile(file)) {
                throw new IOException("daemon launch description not found: " + file
                                      + " (did :maddi-ide-daemon:installDist run?)");
            }
            String main = null;
            List<String> jvm = new ArrayList<>();
            List<String> jars = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) throw new IOException("malformed line in " + file + ": " + line);
                String key = line.substring(0, eq);
                String value = line.substring(eq + 1);
                switch (key) {
                    case "main" -> main = value;
                    case "jvm" -> jvm.add(value);
                    case "lib" -> jars.add(value);
                    default -> throw new IOException("unknown key '" + key + "' in " + file);
                }
            }
            if (main == null || jars.isEmpty()) throw new IOException("incomplete launch description: " + file);
            return new LaunchFile(main, List.copyOf(jvm), List.copyOf(jars));
        }
    }

    /** A running daemon: its process, the port it listens on, and (best-effort) its pid. */
    public record Handle(Process process, int port, long pid) implements Closeable {
        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    /**
     * @param installDir  the daemon {@code installDist} directory (contains {@code bin/} and {@code lib/})
     * @param jdkHome     JDK 25+ home to run the daemon on; {@code null} uses the launcher script default (PATH/JAVA_HOME)
     * @param jvmArgs     extra JVM args (e.g. {@code -Xmx8g}); may be empty
     * @param startTimeoutMillis how long to wait for the {@code DAEMON_PORT=} line
     * @param logFile     file to tee the daemon's stdout/stderr into (appended); {@code null} discards it
     */
    public Handle launch(Path installDir, Path jdkHome, List<String> jvmArgs, long startTimeoutMillis, Path logFile)
            throws IOException {
        return launch(installDir, jdkHome, jvmArgs, Map.of(), startTimeoutMillis, logFile);
    }

    /** As above, with extra environment variables for the daemon process. */
    public Handle launch(Path installDir, Path jdkHome, List<String> jvmArgs, Map<String, String> environment,
                         long startTimeoutMillis, Path logFile)
            throws IOException {
        LaunchFile launchFile = LaunchFile.read(installDir);
        List<String> command = command(jdkHome, System.getenv("JAVA_HOME"), windows(), launchFile, jvmArgs);
        ProcessBuilder pb = new ProcessBuilder(command);
        // the class path is relative to it
        pb.directory(installDir.toFile());
        pb.redirectErrorStream(true);
        // the daemon itself may run builds, which look for JAVA_HOME
        if (jdkHome != null) pb.environment().put("JAVA_HOME", jdkHome.toAbsolutePath().toString());
        // this process, unless the caller names another (a test standing in for an IDE that dies)
        pb.environment().put(ENV_PARENT_PID, Long.toString(ProcessHandle.current().pid()));
        if (environment != null) pb.environment().putAll(environment);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("could not start the maddi daemon with " + command.getFirst()
                                  + (jdkHome == null ? " (no JDK configured: JAVA_HOME, else the PATH)" : "")
                                  + ": " + e.getMessage(), e);
        }
        return awaitPort(process, jdkHome, startTimeoutMillis, logFile);
    }

    /**
     * {@code <java> <the launch file's JVM args> <jvmArgs> -cp lib/<jar>... <main> --port 0}, to run in the install
     * directory. Without a {@code jdkHome}, the {@code java} of {@code JAVA_HOME}, else the one on the {@code PATH},
     * as the start scripts chose.
     */
    static List<String> command(Path jdkHome, String javaHomeVariable, boolean windows, LaunchFile launchFile,
                                List<String> jvmArgs) {
        String exe = windows ? "java.exe" : "java";
        String java;
        if (jdkHome != null) {
            java = jdkHome.toAbsolutePath().resolve("bin").resolve(exe).toString();
        } else if (javaHomeVariable != null && !javaHomeVariable.isBlank()) {
            java = Path.of(javaHomeVariable).resolve("bin").resolve(exe).toString();
        } else {
            java = exe;
        }
        List<String> command = new ArrayList<>();
        command.add(java);
        command.addAll(launchFile.jvmArgs());
        if (jvmArgs != null) command.addAll(jvmArgs);
        command.add("-cp");
        command.add(classPath(launchFile, windows));
        command.add(launchFile.mainClass());
        command.add("--port");
        command.add("0");
        return command;
    }

    /** The jars of {@code lib/}, relative to the install directory, in the launch file's order. */
    static String classPath(LaunchFile launchFile, boolean windows) {
        String separator = windows ? ";" : ":";
        String prefix = windows ? "lib\\" : "lib/";
        StringBuilder classPath = new StringBuilder();
        for (String jar : launchFile.jars()) {
            if (!classPath.isEmpty()) classPath.append(separator);
            classPath.append(prefix).append(jar);
        }
        return classPath.toString();
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private Handle awaitPort(Process process, Path jdkHome, long startTimeoutMillis, Path logFile) throws IOException {
        Writer log = openLog(logFile);
        BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // Read on a thread: readLine blocks, so a daemon that hangs without a word would otherwise never time out,
        // and the deadline was only checked between lines. EOF arrives as END.
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try {
                String line;
                while ((line = stdout.readLine()) != null) lines.add(line);
            } catch (IOException ignored) {
                // the process ended
            } finally {
                lines.add(END);
            }
        }, "maddi-daemon-stdout-start");
        reader.setDaemon(true);
        reader.start();

        int port = -1;
        long pid = process.pid();
        Deque<String> tail = new ArrayDeque<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startTimeoutMillis);
        boolean ended = false;
        try {
            while (port < 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                String line = lines.poll(remaining, TimeUnit.NANOSECONDS);
                if (line == null) break; // timed out
                if (line == END) {
                    ended = true;
                    break;
                }
                appendLog(log, line);
                tail.addLast(line);
                if (tail.size() > TAIL_LINES) tail.removeFirst();
                Matcher pm = PORT_LINE.matcher(line);
                if (pm.find()) port = Integer.parseInt(pm.group(1));
                Matcher idm = PID_LINE.matcher(line);
                if (idm.find()) pid = Long.parseLong(idm.group(1));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (port < 0) {
            String why = startFailure(process, ended, startTimeoutMillis, jdkHome, tail, logFile);
            process.destroyForcibly();
            if (log != null) closeQuietly(log);
            throw new IOException(why);
        }
        // the reader thread keeps draining stdout so the process never blocks on a full pipe (daemon logs there)
        drainInBackground(lines, log);
        return new Handle(process, port, pid);
    }

    /**
     * Why the daemon did not start, in words a user can act on: whether it EXITED (and with what code), or stayed
     * silent; on which JDK; the last lines it printed; and where the full output is.
     */
    static String startFailure(Process process, boolean ended, long startTimeoutMillis, Path jdkHome,
                               Deque<String> tail, Path logFile) {
        StringBuilder sb = new StringBuilder();
        Integer exitCode = null;
        if (ended) {
            try {
                if (process.waitFor(2, TimeUnit.SECONDS)) exitCode = process.exitValue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (ended) {
            sb.append("the maddi daemon exited").append(exitCode == null ? "" : " with code " + exitCode)
                    .append(" before it announced its port");
        } else {
            sb.append("the maddi daemon did not announce its port within ").append(startTimeoutMillis / 1000)
                    .append(" s");
        }
        sb.append(" (JDK: ").append(jdkHome == null ? "JAVA_HOME / PATH" : jdkHome).append(')');
        if (tail.isEmpty()) {
            sb.append(". It printed nothing.");
        } else {
            sb.append(". Its last output:\n");
            tail.forEach(l -> sb.append("    ").append(l).append('\n'));
        }
        if (logFile != null) sb.append("Full output: ").append(logFile);
        return sb.toString().strip();
    }

    private static void drainInBackground(BlockingQueue<String> lines, Writer log) {
        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = lines.take()) != END) appendLog(log, line);
            } catch (InterruptedException ignored) {
                // stop draining
            } finally {
                if (log != null) closeQuietly(log);
            }
        }, "maddi-daemon-stdout-drain");
        t.setDaemon(true);
        t.start();
    }

    private static Writer openLog(Path logFile) {
        if (logFile == null) return null;
        try {
            if (logFile.getParent() != null) Files.createDirectories(logFile.getParent());
            return new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                    StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null; // logging is best-effort; never block the daemon on it
        }
    }

    private static void appendLog(Writer log, String line) {
        if (log == null) return;
        try {
            log.write(line);
            log.write('\n');
            log.flush();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void closeQuietly(Writer log) {
        try {
            log.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
