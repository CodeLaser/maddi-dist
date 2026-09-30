/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.codelaser.maddi.gradleplugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One gate over everything on disk that NAMES a maddi version.
 * <p>
 * {@code gradle.properties} is the single source of truth, and the nine repos that consume maddi are wired by
 * {@code includeBuild} on relative paths, so they hold no version string at all. What is left is a short list of
 * DERIVED artefacts that embed the version in a filename or a coordinate, each reached through a pointer that does
 * not. Nothing compares them to the source of truth, so they rot silently and individually.
 * <p>
 * ⛔ <b>The failure mode is not a missing file, it is a WRONG ANSWER.</b> On 2026-08-18 the daemon distribution at
 * {@code maddi.daemon.install} still held 0.9.0 jars from a week earlier, with no {@code maddi-annotation} jar at
 * all, six days after the rename moved to 0.9.1. The pre-rename daemon has never heard of
 * {@code io.codelaser.maddi.annotation.Container}, so it analysed the fixture cleanly — every phase reported, zero
 * parse errors — and found nothing. The red that reached a human said "expected a contract-violation over the
 * wire", which is a sentence about the analyzer, and the analyzer was fine. A day went into that.
 * <p>
 * Each check below therefore states the version it EXPECTED and the version it FOUND, so the diagnosis is in the
 * failure message rather than three modules away.
 * <p>
 * ⚠ <b>{@code README.md} is deliberately NOT checked.</b> It names the latest coordinate a consumer can actually
 * resolve from Central, which legitimately lags the working version between releases — asserting equality there
 * would be wrong, and asserting nothing is a doc-drift risk this gate does not cover.
 */
public class TestVersionSkew {

    private static final String VERSION = required("maddi.projectVersion");
    private static final Path ROOT = Path.of(required("maddi.rootDir"));
    private static final Path DAEMON_INSTALL = Path.of(required("maddi.daemonInstall"));
    private static final Path PLUGIN_REPO = Path.of(required("maddi.localPluginRepo"));

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("system property " + key + " must be set by the build; without it this"
                                            + " gate would pass by checking nothing");
        }
        return value;
    }

    @DisplayName("the version the build uses is the one gradle.properties declares")
    @Test
    public void theBuildAgreesWithTheSourceOfTruth() throws IOException {
        Path gradleProperties = ROOT.resolve("gradle.properties");
        assertTrue(Files.isRegularFile(gradleProperties), "expected the source of truth at " + gradleProperties);
        String declared = Files.readAllLines(gradleProperties).stream()
                .map(String::strip)
                .filter(l -> l.startsWith("version="))
                .map(l -> l.substring("version=".length()).strip())
                .findFirst().orElseThrow(() -> new AssertionError("no `version=` in " + gradleProperties));
        // Everything else here compares against the injected value, so if THAT has drifted from the file the whole
        // gate is measuring the wrong thing and reporting green.
        assertEquals(declared, VERSION, "the build is using a different version than " + gradleProperties
                                        + " declares; every other check in this class is then meaningless");
    }

    @DisplayName("every maddi jar in the daemon distribution is at its repository's version")
    @Test
    public void theDaemonDistributionCarriesTheProjectVersion() throws IOException {
        Path lib = DAEMON_INSTALL.resolve("lib");
        assertTrue(Files.isDirectory(lib), "expected the daemon distribution at " + lib
                                           + "; the test task must depend on :maddi-ide-daemon:installDist");
        List<String> ours;
        try (Stream<Path> jars = Files.list(lib)) {
            ours = jars.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("maddi-") && n.endsWith(".jar"))
                    .sorted().toList();
        }
        // Refuse the vacuous pass: an empty lib/ would satisfy "every jar matches" without checking anything.
        assertFalse(ours.isEmpty(), "no maddi-*.jar in " + lib + " at all — this gate checked nothing");

        // Since the split each jar carries the version of the repository that builds it: maddi (base), maddi-mod or
        // this one. The tier list says which, and each sibling's gradle.properties says at what version.
        Map<String, String> expected = expectedVersionPerModule();
        List<String> stale = ours.stream().filter(n -> {
            String module = expected.keySet().stream().filter(m -> n.startsWith(m + "-"))
                    .max(java.util.Comparator.comparingInt(String::length)).orElse(null);
            return module == null || !n.equals(module + "-" + expected.get(module) + ".jar");
        }).toList();
        assertTrue(stale.isEmpty(), "the daemon distribution is stale: expected every maddi jar at its repository's"
                                    + " version " + expected + ", found " + stale + " in " + lib
                                    + "\nA daemon built before a rename does not fail, it analyses cleanly and"
                                    + " reports nothing — see this class's javadoc.");
    }

    /** module name -> the version of the repository it lives in, from the tier list and the siblings' properties. */
    private static Map<String, String> expectedVersionPerModule() throws IOException {
        Path maddi = ROOT.resolveSibling("maddi");
        Map<String, String> versionPerTier = Map.of(
                "base", declaredVersion(maddi.resolve("gradle.properties")),
                "mod", declaredVersion(ROOT.resolveSibling("maddi-mod").resolve("gradle.properties")),
                "dist", VERSION);
        Map<String, String> result = new TreeMap<>();
        for (String line : Files.readAllLines(maddi.resolve("build-logic/src/main/resources/tiers.txt"))) {
            String[] parts = line.split("#", 2)[0].trim().split("\\s+");
            if (parts.length == 2 && versionPerTier.containsKey(parts[1])) {
                result.put(parts[0], versionPerTier.get(parts[1]));
            }
        }
        assertFalse(result.isEmpty(), "no tiers read from " + maddi + "; this gate would check nothing");
        return result;
    }

    private static String declaredVersion(Path gradleProperties) throws IOException {
        return Files.readAllLines(gradleProperties).stream().map(String::strip)
                .filter(l -> l.startsWith("version=")).map(l -> l.substring("version=".length()).strip())
                .findFirst().orElseThrow(() -> new AssertionError("no `version=` in " + gradleProperties));
    }

    @DisplayName("the local plugin repository carries the project version, not only older ones")
    @Test
    public void theLocalPluginRepoPublishesTheProjectVersion() throws IOException {
        assertTrue(Files.isDirectory(PLUGIN_REPO), "expected the local plugin repository at " + PLUGIN_REPO
                + "; the test task must depend on publishAllPublicationsToLocalPluginRepoRepository");
        Path jar = PLUGIN_REPO.resolve("io/codelaser/maddi-gradleplugin/" + VERSION
                                       + "/maddi-gradleplugin-" + VERSION + ".jar");
        assertTrue(Files.isRegularFile(jar), "the local plugin repository has no artefact at the project version."
                + "\n  expected: " + jar
                + "\n  present:  " + publishedVersions()
                + "\nTestAnalyzerPluginShadedJarIsolation formats the project version into a generated build"
                + " script and resolves from here, so a skew fails there as an unresolvable plugin.");
    }

    /** What the repository actually holds, so the failure names the skew rather than only the absence. */
    private static List<String> publishedVersions() throws IOException {
        Path artifact = PLUGIN_REPO.resolve("io/codelaser/maddi-gradleplugin");
        if (!Files.isDirectory(artifact)) return List.of("<no io/codelaser/maddi-gradleplugin at all>");
        try (Stream<Path> versions = Files.list(artifact)) {
            return versions.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
