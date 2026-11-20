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

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the Kotlin front end for the daemon, on demand, so the IDE plugins need not carry it.
 * <p>
 * The K2 compiler cannot be published to Maven Central (PUBLISHING.md, "Why Kotlin is separate"); it ships only
 * inside the {@code maddi-kotlin-<version>.zip} CLI distribution, as its {@code lib-k2/} directory — exactly the
 * directory of jars {@code K2Realm} loads. So installing means: fetch that zip for the daemon's own version, keep
 * {@code lib-k2/*.jar} and nothing else, and hand the directory to the daemon ({@link #ENV_K2_HOME}).
 * <p>
 * ⚠ The version must be the daemon's. The realm shares the CST and the {@code maddi-kotlin-api} contract with the
 * host, so K2 jars from another release load and then disagree with the daemon's classes at the boundary.
 */
public final class KotlinFrontEndInstaller {

    /** Read by the daemon at startup (DaemonMain), which turns it into {@code -Dmaddi.k2.home}. */
    public static final String ENV_K2_HOME = "MADDI_K2_HOME";

    /** Where the CLI distributions are published (release-cli.sh): one release per tag {@code v<version>}. */
    public static final String RELEASE_BASE = "https://github.com/CodeLaser/maddi/releases/download";

    private static final String LIB_K2 = "lib-k2/";
    /** The one jar every valid K2 directory must hold: the front end itself, which the realm finds by service. */
    public static final String FRONT_END_JAR_PREFIX = "maddi-kotlin-k2";
    /** The one jar every distribution's {@code lib/} must hold: the mixed Java+Kotlin driver. */
    public static final String DRIVER_JAR_PREFIX = "maddi-run-kotlin";
    private static final String COMPLETE_MARKER = ".complete";

    private final Path cacheRoot;

    /** @param cacheRoot a directory the installer owns; each version gets {@code k2-<version>/} inside it */
    public KotlinFrontEndInstaller(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    /** The published CLI zip for a release. */
    public static URI releaseZip(String version) {
        return URI.create(RELEASE_BASE + "/v" + version + "/maddi-kotlin-" + version + ".zip");
    }

    /** The directory for a version, whether or not it is installed. */
    public Path homeFor(String version) {
        return cacheRoot.resolve("k2-" + version);
    }

    /** The installed directory of K2 jars for {@code version}, or {@code null} when it is not (completely) there. */
    public Path installed(String version) {
        Path home = homeFor(version);
        return Files.isRegularFile(home.resolve(COMPLETE_MARKER)) && holdsFrontEnd(home) ? home : null;
    }

    /** Download the release zip for {@code version} and install its K2 jars; see {@link #install(String, URI, LongConsumer)}. */
    public Path install(String version, LongConsumer bytesRead) throws IOException {
        return install(version, releaseZip(version), bytesRead);
    }

    /**
     * Install the K2 jars of the distribution at {@code zip}, which must match the SHA-256 published beside it as
     * {@code <zip>.sha256} (release-cli.sh writes it, in {@code sha256sum} format). The zip is streamed, never unpacked whole: only
     * entries under a {@code lib-k2/} directory ending in {@code .jar} are kept, each by its file name alone (so no
     * entry can write outside the target). The target is filled under a temporary name and moved into place only
     * when it holds the front end, so a failed or interrupted download never leaves a directory that looks
     * installed.
     *
     * @param bytesRead progress, in bytes of the zip read so far; may be {@code null}
     * @return the installed directory
     * @throws IOException with a message fit for the user: the release does not exist, it has no checksum or the
     *                     zip does not match it, or its zip carries no {@code lib-k2/} (a release published before
     *                     the Kotlin front end could be downloaded)
     */
    public Path install(String version, URI zip, LongConsumer bytesRead) throws IOException {
        String expected = publishedChecksum(version, zip);
        return extract(zip, expected, homeFor(version), KotlinFrontEndInstaller::k2Entry,
                KotlinFrontEndInstaller::holdsFrontEnd, version + "\n", bytesRead,
                "the maddi " + version + " Kotlin distribution at " + zip,
                "holds no Kotlin front end (no " + FRONT_END_JAR_PREFIX + " jar under lib-k2/); it may predate"
                + " downloadable Kotlin support");
    }

    /** The directory for a whole distribution of {@code version}, whether or not it is installed. */
    public Path distributionHomeFor(String version) {
        return cacheRoot.resolve("dist-" + version);
    }

    /**
     * The installed CLI distribution of {@code version} ({@link #installDistribution(String, URI, LongConsumer)}),
     * or {@code null} when it is not (completely) there.
     */
    public Path installedDistribution(String version) {
        return installedDistributionAt(distributionHomeFor(version));
    }

    private static Path installedDistributionAt(Path home) {
        return Files.isRegularFile(home.resolve(COMPLETE_MARKER)) && holdsDistribution(home) ? home : null;
    }

    /** Download the release zip for {@code version} and install it whole; see {@link #installDistribution(String, URI, LongConsumer)}. */
    public Path installDistribution(String version, LongConsumer bytesRead) throws IOException {
        return installDistribution(version, releaseZip(version), bytesRead);
    }

    /**
     * Install the whole CLI distribution at {@code zip}, for a client that runs the Kotlin analyzer itself rather
     * than through the daemon (the Gradle plugin): its {@code lib/} (the driver and engine, the launcher's class
     * path) and its {@code lib-k2/} (the realm), as {@code <home>/lib} and {@code <home>/lib-k2}. The checksum, the
     * streaming and the all-or-nothing move are those of {@link #install(String, URI, LongConsumer)}; only jars
     * directly inside a directory named {@code lib} or {@code lib-k2} are kept, each by its file name.
     *
     * @return the installed home, holding {@code lib/} and {@code lib-k2/}
     */
    public Path installDistribution(String version, URI zip, LongConsumer bytesRead) throws IOException {
        String expected = publishedChecksum(version, zip);
        return extract(zip, expected, distributionHomeFor(version), KotlinFrontEndInstaller::distributionEntry,
                KotlinFrontEndInstaller::holdsDistribution, version + "\n", bytesRead,
                "the maddi " + version + " Kotlin distribution at " + zip, DISTRIBUTION_SHAPE);
    }

    /**
     * Install a distribution zip from the local file system: one the user built or downloaded and pointed at, so
     * there is no published checksum to hold it to. It is installed under its own SHA-256, which makes a rebuilt
     * zip a new install and an unchanged one a cache hit.
     */
    public Path installDistribution(Path zipFile) throws IOException {
        String sha = sha256Of(zipFile);
        Path home = cacheRoot.resolve("local-" + sha.substring(0, 16));
        Path installed = installedDistributionAt(home);
        if (installed != null) return installed;
        return extract(zipFile.toUri(), sha, home, KotlinFrontEndInstaller::distributionEntry,
                KotlinFrontEndInstaller::holdsDistribution, zipFile + "\n", null,
                "the Kotlin distribution " + zipFile, DISTRIBUTION_SHAPE);
    }

    private static final String DISTRIBUTION_SHAPE = "is not a maddi Kotlin distribution (it needs "
            + DRIVER_JAR_PREFIX + "*.jar under lib/ and " + FRONT_END_JAR_PREFIX + "*.jar under lib-k2/)";

    /**
     * Whether {@code home} has the shape of an unpacked CLI distribution: the mixed driver in {@code lib/} and the
     * front end in {@code lib-k2/}. Also the check on a directory the user points at directly.
     */
    public static boolean holdsDistribution(Path home) {
        return holdsJar(home.resolve("lib"), DRIVER_JAR_PREFIX) && holdsFrontEnd(home.resolve("lib-k2"));
    }

    /** K2 mode: {@code .../lib-k2/X.jar} becomes {@code X.jar}. */
    private static String k2Entry(String name) {
        int at = name.lastIndexOf(LIB_K2);
        if (at < 0 || !name.endsWith(".jar")) return null;
        String fileName = name.substring(at + LIB_K2.length());
        return fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\") ? null : fileName;
    }

    /** Distribution mode: {@code .../lib/X.jar} and {@code .../lib-k2/X.jar} keep their directory and file name. */
    private static String distributionEntry(String name) {
        if (!name.endsWith(".jar") || name.contains("\\")) return null;
        String[] segments = name.split("/");
        if (segments.length < 2) return null;
        String dir = segments[segments.length - 2];
        String fileName = segments[segments.length - 1];
        if (fileName.isEmpty() || !("lib".equals(dir) || "lib-k2".equals(dir))) return null;
        return dir + "/" + fileName;
    }

    /**
     * The shared core: stream {@code zip}, keep the entries {@code target} maps to a relative path (never more than
     * {@code dir/name}, so nothing lands outside the staging directory), check the whole file against
     * {@code expected}, check the result with {@code valid}, and only then move it into place as {@code home}.
     */
    private Path extract(URI zip, String expected, Path home, Function<String, String> target, Predicate<Path> valid,
                         String marker, LongConsumer bytesRead, String what, String invalid) throws IOException {
        Files.createDirectories(cacheRoot);
        Path staging = Files.createTempDirectory(cacheRoot, home.getFileName() + "-");
        try {
            MessageDigest sha256 = sha256();
            try (InputStream raw = open(zip);
                 DigestInputStream digest = new DigestInputStream(raw, sha256);
                 ZipInputStream in = new ZipInputStream(new CountingInputStream(digest, bytesRead))) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String relative = target.apply(entry.getName());
                    if (relative == null) continue;
                    Path file = staging.resolve(relative);
                    Files.createDirectories(file.getParent());
                    Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                }
                // the zip reader stops before the central directory; the checksum is over the whole file
                digest.transferTo(OutputStream.nullOutputStream());
            }
            String actual = HexFormat.of().formatHex(sha256.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException(what + " does not match its published SHA-256 (expected " + expected + ", got "
                                      + actual + "); nothing was installed");
            }
            if (!valid.test(staging)) {
                throw new IOException(what + " " + invalid);
            }
            Files.writeString(staging.resolve(COMPLETE_MARKER), marker);
            deleteRecursively(home);
            Files.move(staging, home, StandardCopyOption.ATOMIC_MOVE);
            return home;
        } catch (FileNotFoundException e) {
            throw new IOException("no " + what.replaceFirst("^the ", ""), e);
        } finally {
            deleteRecursively(staging);
        }
    }

    private static String sha256Of(Path file) throws IOException {
        MessageDigest sha256 = sha256();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), sha256)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(sha256.digest());
    }

    /** The hex digest in {@code <zip>.sha256}: its first token, as {@code sha256sum} writes it. */
    private static String publishedChecksum(String version, URI zip) throws IOException {
        URI checksum = URI.create(zip + ".sha256");
        String content;
        try (InputStream in = open(checksum)) {
            content = new String(in.readNBytes(4096), StandardCharsets.US_ASCII).trim();
        } catch (FileNotFoundException e) {
            throw new IOException("no maddi " + version + " Kotlin distribution at " + zip
                                  + " (or no checksum published beside it)", e);
        }
        String hex = content.split("\\s+", 2)[0];
        if (!hex.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("the checksum at " + checksum + " is not a SHA-256: " + content);
        }
        return hex;
    }

    private static InputStream open(URI uri) throws IOException {
        URLConnection connection = uri.toURL().openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        return connection.getInputStream();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    private static boolean holdsFrontEnd(Path dir) {
        return holdsJar(dir, FRONT_END_JAR_PREFIX);
    }

    private static boolean holdsJar(Path dir, String prefix) {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .anyMatch(n -> n.startsWith(prefix) && n.endsWith(".jar"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Whether any of the directories holds a Kotlin source file. Stops at the first one: this decides whether to
     * offer the download at all, and a Java project must not pay for a full walk of its tree to learn that.
     */
    public static boolean anyKotlinSource(List<String> directories) {
        for (String directory : directories) {
            Path dir = Path.of(directory);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> paths = Files.walk(dir)) {
                if (paths.anyMatch(p -> p.getFileName().toString().endsWith(".kt"))) return true;
            } catch (IOException | UncheckedIOException e) {
                // an unreadable directory says nothing either way
            }
        }
        return false;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final LongConsumer bytesRead;
        private long count;

        CountingInputStream(InputStream in, LongConsumer bytesRead) {
            super(in);
            this.bytesRead = bytesRead;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) advance(1);
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) advance(n);
            return n;
        }

        private void advance(long n) {
            count += n;
            if (bytesRead != null) bytesRead.accept(count);
        }
    }
}
