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

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/**
 * {@code maddi-mcp}: an MCP server on stdin/stdout. Options, each with an environment variable as fallback:
 * <pre>
 *   --daemon-install DIR   MADDI_DAEMON_INSTALL   the daemon distribution; default: daemon/ next to lib/
 *   --jdk DIR              MADDI_JDK_HOME         a JDK 25+ to run the daemon on; default: this JVM's, if 25+
 *   --xmx-mb N             MADDI_DAEMON_XMX_MB    the daemon's heap; default: the JVM's default
 *   --log FILE             MADDI_DAEMON_LOG       the daemon's log; default: in the temporary directory
 *   --store DIR            MADDI_MCP_STORE        where configured projects are kept; default: ~/.maddi/mcp
 *   --incremental BOOL     MADDI_INCREMENTAL      after an edit, re-analyse only what changed (never certified;
 *                                                 analyze with cold for a full run); default: false
 * </pre>
 * stdout carries the protocol and nothing else: everything else goes to stderr or the log.
 */
public final class McpMain {
    private McpMain() {
    }

    public static void main(String[] args) throws Exception {
        MaddiService.DaemonSettings settings;
        try {
            settings = settings(args);
        } catch (IllegalArgumentException e) {
            System.err.println("maddi-mcp: " + e.getMessage());
            System.exit(2);
            return;
        }
        System.err.println("maddi-mcp: daemon " + settings.installDir() + " on " + settings.jdkHome()
                           + (settings.incremental() ? ", incremental" : "") + ", log " + settings.logFile());
        // the client ends the session by closing stdin; the daemon exits with this process (MADDI_PARENT_PID)
        CountDownLatch closed = new CountDownLatch(1);
        InputStream in = new FilterInputStream(System.in) {
            @Override
            public int read() throws IOException {
                return eof(super.read());
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return eof(super.read(b, off, len));
            }

            private int eof(int n) {
                if (n < 0) closed.countDown();
                return n;
            }
        };
        String storeOption = option(args, "--store", "MADDI_MCP_STORE");
        Path store = storeOption != null ? Path.of(storeOption)
                : Path.of(System.getProperty("user.home"), ".maddi", "mcp");
        MaddiService service = new MaddiService(settings, store);
        System.err.println("maddi-mcp: projects stored in " + store);
        McpSyncServer server = start(service, in, System.out);
        Runtime.getRuntime().addShutdownHook(new Thread(service::close));
        closed.await();
        server.closeGracefully();
        System.exit(0);
    }

    /** Serve the tools on these streams; the caller owns the service. */
    public static McpSyncServer start(MaddiService service, InputStream in, OutputStream out) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(service.mapper());
        StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper, in, out);
        return McpServer.sync(transport)
                .serverInfo("maddi", version())
                .instructions(McpTools.INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                // run_status may wait 600 s for a run
                .requestTimeout(Duration.ofMinutes(11))
                .tools(new McpTools(service, jsonMapper).specifications())
                .build();
    }

    static MaddiService.DaemonSettings settings(String[] args) {
        String install = option(args, "--daemon-install", "MADDI_DAEMON_INSTALL");
        String jdk = option(args, "--jdk", "MADDI_JDK_HOME");
        String xmx = option(args, "--xmx-mb", "MADDI_DAEMON_XMX_MB");
        String log = option(args, "--log", "MADDI_DAEMON_LOG");
        String incremental = option(args, "--incremental", "MADDI_INCREMENTAL");
        if (incremental != null && !incremental.equals("true") && !incremental.equals("false")) {
            throw new IllegalArgumentException("--incremental is true or false, not '" + incremental + "'");
        }

        Path installDir = install != null ? Path.of(install) : bundledDaemon();
        if (installDir == null || !Files.isDirectory(installDir.resolve("lib"))) {
            throw new IllegalArgumentException("no daemon distribution at " + installDir + ": pass --daemon-install");
        }
        Path jdkHome = jdk != null ? Path.of(jdk) : Path.of(System.getProperty("java.home"));
        int feature = featureVersion(jdkHome);
        if (feature < 25) {
            throw new IllegalArgumentException("the daemon needs a JDK 25 or later; " + jdkHome + " is "
                                               + (feature < 0 ? "not a JDK" : feature) + ": pass --jdk");
        }
        Path logFile = log != null ? Path.of(log)
                : Path.of(System.getProperty("java.io.tmpdir"), "maddi-mcp-daemon-" + ProcessHandle.current().pid() + ".log");
        return new MaddiService.DaemonSettings(installDir.toAbsolutePath(), jdkHome.toAbsolutePath(),
                xmx == null ? 0 : Integer.parseInt(xmx), logFile, Boolean.parseBoolean(incremental));
    }

    private static String option(String[] args, String name, String environment) {
        for (int i = 0; i < args.length - 1; i++) if (name.equals(args[i])) return args[i + 1];
        for (String arg : args) if (arg.startsWith(name + "=")) return arg.substring(name.length() + 1);
        String value = System.getenv(environment);
        return value == null || value.isBlank() ? null : value;
    }

    /** {@code <home>/daemon}, where the distribution puts it: home is the directory holding lib/maddi-mcp.jar. */
    private static Path bundledDaemon() {
        try {
            Path jar = Path.of(McpMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path home = jar.getParent() == null ? null : jar.getParent().getParent();
            return home == null ? null : home.resolve("daemon");
        } catch (Exception e) {
            return null;
        }
    }

    /** The feature version from a JDK's {@code release} file; -1 if there is none. */
    static int featureVersion(Path jdkHome) {
        Path release = jdkHome.resolve("release");
        if (!Files.isRegularFile(release)) return -1;
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(release)) {
            properties.load(in);
        } catch (IOException e) {
            return -1;
        }
        String version = properties.getProperty("JAVA_VERSION", "").replace("\"", "");
        try {
            int end = 0;
            while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
            return Integer.parseInt(version.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String version() {
        String version = McpMain.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
