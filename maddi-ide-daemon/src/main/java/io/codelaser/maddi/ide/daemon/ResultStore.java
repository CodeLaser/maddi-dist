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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * -Dmaddi.daemon.persist: the results of earlier runs, on disk, so that a daemon that starts again (an IDE or an MCP
 * server restarted, or one daemon switching between projects) answers a request whose inputs did not change with the
 * result it computed before, instead of analysing the project again. The analysis is deterministic, so the same
 * inputs give the same result, certainty included: a certified result read back is still certified.
 * <p>
 * One entry per configuration: the request's {@link DaemonProtocol.AnalyzeConfig} and the service's own settings name
 * the file. An entry is used only when its key matches the request's in full:
 * <ul>
 *     <li>the engine: the content of every jar and class file on this daemon's class path, which covers maddi's
 *     code and the analysis hints bundled with it, whatever its version string says;</li>
 *     <li>the JDK the daemon runs on, and the one the configuration names;</li>
 *     <li>the class path of the project: a jar by its path, size and modification time (a jar in a build cache does
 *     not change in place), a directory, such as a compiler's output, by the content of its files;</li>
 *     <li>the sources: the content of every file under every source directory.</li>
 * </ul>
 * The key is computed before the parse: a file that changes during the run then makes the stored entry miss, never
 * hit with a result of other sources. Only the result is stored, not the model: the daemon's other requests
 * ({@code libraryAnnotations}, {@code translate}) need one, and {@link WarmAnalysisService} builds it with a cold run
 * when they come after a result that was read back.
 * <p>
 * Nothing here may cost the daemon a request: a store that cannot be read or written is logged and ignored.
 */
public final class ResultStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResultStore.class);

    /** The switch: a directory, or {@code true} for {@link #DEFAULT_DIRECTORY}. Absent or blank: off. */
    static final String PROPERTY = "maddi.daemon.persist";
    static final Path DEFAULT_DIRECTORY = Path.of(System.getProperty("user.home"), ".maddi", "daemon-results");
    /** Bumped when the entry's shape changes; an entry of another format is a miss. */
    static final int FORMAT = 1;
    /** The number of entries kept; the least recently used go first. */
    static final int MAX_ENTRIES = 32;

    /** What an entry must match: the parts are kept apart so that a miss can say which one moved. */
    public record Key(String engine, String jdk, String classPath, String sources) {
    }

    private record Entry(int format, String configuration, Key key, DaemonProtocol.Result result) {
    }

    private final Path directory;
    private final ObjectMapper mapper = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();
    private String engine; // computed once: the class path does not change while the daemon runs

    public ResultStore(Path directory) {
        this.directory = directory;
    }

    /** The store the system property asks for, or null. */
    static ResultStore fromSystemProperty() {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank() || "false".equals(value)) return null;
        return new ResultStore("true".equals(value) ? DEFAULT_DIRECTORY : Path.of(value));
    }

    Path directory() {
        return directory;
    }

    /**
     * The configuration as the entry's name sees it: the request's, and {@code settings}, the service's own choices
     * that change the result (the static-side-effect analysis, for one).
     */
    String configuration(DaemonProtocol.AnalyzeConfig config, String settings) throws IOException {
        return mapper.writeValueAsString(config) + "\n" + settings;
    }

    /** The key of a request, at this moment. */
    Key key(DaemonProtocol.AnalyzeConfig config) throws IOException {
        Path work = config.workingDirectory() == null ? Path.of(".") : Path.of(config.workingDirectory());

        MessageDigest jdk = md5();
        update(jdk, System.getProperty("java.home"));
        update(jdk, System.getProperty("java.runtime.version"));
        if (config.sdkHome() != null) {
            update(jdk, config.sdkHome());
            Path release = Path.of(config.sdkHome()).resolve("release");
            if (Files.isRegularFile(release)) jdk.update(Files.readAllBytes(release));
        }

        TreeSet<String> classPathEntries = new TreeSet<>();
        if (config.classpath() != null) {
            for (DaemonProtocol.ClasspathEntry entry : config.classpath()) {
                classPathEntries.add(entry.path());
            }
        }
        TreeSet<String> sourceDirectories = new TreeSet<>();
        if (config.sources() != null) {
            for (DaemonProtocol.SourceRoot root : config.sources()) sourceDirectories.add(root.path());
        }
        if (config.moduleSourceSets() != null) {
            for (DaemonProtocol.ModuleSourceSet set : config.moduleSourceSets()) {
                if (set.classPath() != null) classPathEntries.addAll(set.classPath());
                if (set.sourceDirectories() != null) sourceDirectories.addAll(set.sourceDirectories());
            }
        }
        MessageDigest classPath = md5();
        for (String entry : classPathEntries) {
            if (entry == null || entry.isBlank()) continue;
            update(classPath, entry);
            Path path = resolve(work, entry);
            if (path == null) continue; // jmod:java.base and the like: named by the configuration and the JDK
            if (Files.isDirectory(path)) {
                contents(classPath, path);
            } else if (Files.isRegularFile(path)) {
                update(classPath, Files.size(path) + "@" + Files.getLastModifiedTime(path).toMillis());
            } else {
                update(classPath, "absent");
            }
        }
        MessageDigest sources = md5();
        for (String dir : sourceDirectories) {
            if (dir == null || dir.isBlank()) continue;
            update(sources, dir);
            Path path = resolve(work, dir);
            if (path != null && Files.isDirectory(path)) contents(sources, path);
            else update(sources, "absent");
        }
        return new Key(engine(), hex(jdk), hex(classPath), hex(sources));
    }

    /** The stored result of this configuration, if its key is {@code key}; null otherwise. */
    DaemonProtocol.Result lookup(String configuration, Key key) {
        Path file = fileOf(configuration);
        if (!Files.isRegularFile(file)) {
            LOGGER.info("persist: no stored result for this configuration");
            return null;
        }
        try {
            Entry entry = mapper.readValue(file.toFile(), Entry.class);
            if (entry.format() != FORMAT || !configuration.equals(entry.configuration()) || entry.key() == null
                || entry.result() == null) {
                LOGGER.info("persist: the stored result is of another format or configuration");
                return null;
            }
            if (!key.equals(entry.key())) {
                LOGGER.info("persist: the stored result is out of date, {} changed", changed(entry.key(), key));
                return null;
            }
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis())); // recently used
            return entry.result();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("persist: cannot read {}, ignoring it: {}", file, e.toString());
            return null;
        }
    }

    /** Store {@code result} as this configuration's, for inputs {@code key}. */
    void save(String configuration, Key key, DaemonProtocol.Result result) {
        Path file = fileOf(configuration);
        try {
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, "entry", ".tmp");
            try {
                mapper.writeValue(temp.toFile(), new Entry(FORMAT, configuration, key, result));
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            LOGGER.info("persist: stored the result ({} element annotations, {} findings) in {}",
                    result.elementAnnotations().size(), result.findings().size(), file);
            evict();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("persist: cannot store the result in {}: {}", file, e.toString());
        }
    }

    private void evict() throws IOException {
        List<Path> entries;
        try (Stream<Path> list = Files.list(directory)) {
            entries = new ArrayList<>(list.filter(p -> p.getFileName().toString().endsWith(".json")).toList());
        }
        if (entries.size() <= MAX_ENTRIES) return;
        entries.sort(Comparator.comparing(ResultStore::lastModified).reversed());
        for (Path old : entries.subList(MAX_ENTRIES, entries.size())) Files.deleteIfExists(old);
    }

    private static FileTime lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private Path fileOf(String configuration) {
        MessageDigest digest = sha256();
        update(digest, configuration);
        return directory.resolve(hex(digest) + ".json");
    }

    private static String changed(Key stored, Key now) {
        List<String> parts = new ArrayList<>();
        if (!now.engine().equals(stored.engine())) parts.add("the engine");
        if (!now.jdk().equals(stored.jdk())) parts.add("the JDK");
        if (!now.classPath().equals(stored.classPath())) parts.add("the class path");
        if (!now.sources().equals(stored.sources())) parts.add("the sources");
        return String.join(", ", parts);
    }

    /*
     The daemon's own code, by content: the class path, and every jar beside the one this class came from (the
     installDist lib directory), so that a launcher that hides the class path behind one jar's manifest still names
     the code that runs.
     */
    private synchronized String engine() throws IOException {
        if (engine == null) {
            TreeSet<Path> parts = new TreeSet<>();
            for (String part : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                if (!part.isBlank()) parts.add(Path.of(part).toAbsolutePath().normalize());
            }
            try {
                var codeSource = ResultStore.class.getProtectionDomain().getCodeSource();
                if (codeSource != null) {
                    Path own = Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
                    parts.add(own);
                    if (Files.isRegularFile(own) && own.getParent() != null) {
                        try (Stream<Path> list = Files.list(own.getParent())) {
                            list.filter(p -> p.getFileName().toString().endsWith(".jar"))
                                    .forEach(p -> parts.add(p.toAbsolutePath().normalize()));
                        }
                    }
                }
            } catch (java.net.URISyntaxException | RuntimeException e) {
                LOGGER.debug("persist: no code source for the engine key: {}", e.toString());
            }
            MessageDigest digest = md5();
            for (Path path : parts) {
                if (Files.isDirectory(path)) {
                    update(digest, "dir");
                    contents(digest, path);
                } else if (Files.isRegularFile(path)) {
                    update(digest, path.getFileName().toString());
                    file(digest, path);
                }
            }
            engine = hex(digest);
        }
        return engine;
    }

    private static Path resolve(Path work, String entry) {
        if (entry.startsWith("jmod:")) return null;
        try {
            Path path = Path.of(entry);
            return path.isAbsolute() ? path : work.resolve(path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /* every regular file under root, in a fixed order: its path relative to root, and its content */
    private static void contents(MessageDigest digest, Path root) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            update(digest, root.relativize(file).toString().replace('\\', '/'));
            file(digest, file);
        }
    }

    private static void file(MessageDigest digest, Path file) throws IOException {
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
        }
    }

    private static void update(MessageDigest digest, String s) {
        digest.update(String.valueOf(s).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
