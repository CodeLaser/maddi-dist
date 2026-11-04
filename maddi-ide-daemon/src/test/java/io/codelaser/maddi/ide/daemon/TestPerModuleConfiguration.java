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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;

/**
 * The per-module configuration must give each source set ITS OWN class path, not the project-wide union.
 * <p>
 * The flat form cannot: {@code InputConfigurationImpl.Builder.build()} wires every string-style source
 * directory to {@code classPathParts + all earlier sourceSets}. Measured on the CodeLaser tree, that is
 * 160 x 491 = 75,520 (set, entry) pairs where the javac-log configuration declares 5,524, and javac opens a
 * ZipFileSystem per container per source set — two OOMs, at 12 GB and at 24 GB, before a line of project
 * source was parsed.
 * <p>
 * The second property here is the one that is easy to lose again: a source set's output location is its
 * IDENTITY, and must never appear as a class-path part. When it does, every FQN becomes reachable both as
 * source and as bytecode.
 */
public class TestPerModuleConfiguration {

    private static final String A_JAR = "/libs/only-a-needs-this.jar";
    private static final String B_JAR = "/libs/only-b-needs-this.jar";

    /** a/main <- b/main <- b/test, each with a jar nobody else declares. */
    private static DaemonProtocol.AnalyzeConfig perModuleConfig() {
        DaemonProtocol.ModuleSourceSet a = new DaemonProtocol.ModuleSourceSet(
                "a/main", List.of("/p/a/src/main/java"), "/p/a/build/classes/java/main",
                false, 21, List.of(), List.of(A_JAR));
        DaemonProtocol.ModuleSourceSet b = new DaemonProtocol.ModuleSourceSet(
                "b/main", List.of("/p/b/src/main/java"), "/p/b/build/classes/java/main",
                false, 21, List.of("a/main"), List.of(B_JAR));
        DaemonProtocol.ModuleSourceSet bTest = new DaemonProtocol.ModuleSourceSet(
                "b/test", List.of("/p/b/src/test/java"), "/p/b/build/classes/java/test",
                true, 21, List.of("b/main"), List.of());
        return new DaemonProtocol.AnalyzeConfig("/p", null, "UTF-8", List.of("java.base"),
                List.of(), List.of(), List.of(), false, false, List.of(a, b, bTest));
    }

    private static SourceSet named(InputConfiguration configuration, String name) {
        SourceSet found = configuration.sourceSets().stream()
                .filter(s -> name.equals(s.name())).findFirst().orElse(null);
        assertNotNull(found, "no source set named " + name + "; have "
                            + configuration.sourceSets().stream().map(SourceSet::name).toList());
        return found;
    }

    private static Set<String> dependencyNames(SourceSet set) {
        return set.dependencies().stream().map(SourceSet::name).collect(Collectors.toSet());
    }

    @Test
    public void testEachSetGetsOnlyItsOwnClassPath() {
        InputConfiguration configuration = new InputConfigurationAssembler().build(perModuleConfig());

        Set<String> aDeps = dependencyNames(named(configuration, "a/main"));
        Set<String> bDeps = dependencyNames(named(configuration, "b/main"));

        // b declares its own jar and a/main; it must NOT have been handed a's jar as well. a's jar still
        // reaches b transitively through a/main — SourceSetImpl.recursiveDependencies computes the closure —
        // so the direct list staying small is exactly the point, not a loss.
        assertTrue(bDeps.contains("only-b-needs-this.jar"), "b lost its own jar: " + bDeps);
        assertTrue(bDeps.contains("a/main"), "b lost its module dependency: " + bDeps);
        assertFalse(bDeps.contains("only-a-needs-this.jar"),
                "b was handed a's jar: the project-wide union is back. deps=" + bDeps);

        assertTrue(aDeps.contains("only-a-needs-this.jar"), "a lost its own jar: " + aDeps);
        assertFalse(aDeps.contains("only-b-needs-this.jar"),
                "a was handed b's jar: the project-wide union is back. deps=" + aDeps);
        // a is first in the list and depends on no module; the flat builder would still have wired it to
        // every class-path part, so an empty-of-foreign-jars a is the sharpest signal there is
        assertFalse(aDeps.contains("b/main"), "a depends on b: ordering, not declarations, drove the wiring");
    }

    @Test
    public void testOutputDirsAreIdentitiesNotClassPathParts() {
        InputConfiguration configuration = new InputConfigurationAssembler().build(perModuleConfig());

        Set<URI> outputs = configuration.sourceSets().stream().map(SourceSet::uri).collect(Collectors.toSet());
        Set<URI> parts = configuration.classPathParts().stream().map(SourceSet::uri).collect(Collectors.toSet());
        Set<URI> overlap = outputs.stream().filter(parts::contains).collect(Collectors.toSet());

        // the javac-log configuration for the whole CodeLaser tree has exactly this: 0 of 160 outputs on the
        // 491-part class path. The IDE path used to have all of them.
        assertTrue(overlap.isEmpty(), "a source set's own output is on the class path, so its types are "
                                      + "reachable as source AND bytecode: " + overlap);
        assertEquals(3, outputs.size(), "expected one identity per source set");
    }

    @Test
    public void testTestSetAndReleaseAndModuleFlagSurvive() {
        InputConfiguration configuration = new InputConfigurationAssembler().build(perModuleConfig());
        assertTrue(named(configuration, "b/test").test(), "the test set lost its test flag");
        assertFalse(named(configuration, "b/main").test());
        // a dropped sourceRelease silently reinstates "whatever JDK maddi runs on" for that set
        assertEquals(21, named(configuration, "a/main").sourceRelease(), "sourceRelease was dropped");
    }

    /**
     * The contrast, so the property above is attributable to the per-module path and not to something else in
     * the assembler: the SAME project described flatly gets every set wired to everything.
     */
    @Test
    public void testFlatFormStillAutoWires() {
        DaemonProtocol.AnalyzeConfig flat = new DaemonProtocol.AnalyzeConfig(
                "/p", null, "UTF-8", List.of("java.base"),
                List.of(new DaemonProtocol.SourceRoot("a/main", "/p/a/src/main/java", false),
                        new DaemonProtocol.SourceRoot("b/main", "/p/b/src/main/java", false)),
                List.of(new DaemonProtocol.ClasspathEntry(A_JAR, "compile"),
                        new DaemonProtocol.ClasspathEntry(B_JAR, "compile")),
                List.of(), false, false);
        InputConfiguration configuration = new InputConfigurationAssembler().build(flat);

        Set<String> aDeps = dependencyNames(named(configuration, "a/main"));
        assertTrue(aDeps.contains("only-a-needs-this.jar") && aDeps.contains("only-b-needs-this.jar"),
                "the flat form is supposed to auto-wire every set to every part; if this fails the fallback "
                + "changed and the per-module assertions above no longer prove a contrast. deps=" + aDeps);
    }

    @Test
    public void testRestrictToPackagesReachesEverySourceSet() {
        DaemonProtocol.AnalyzeConfig all = perModuleConfig();
        DaemonProtocol.AnalyzeConfig restricted = new DaemonProtocol.AnalyzeConfig(all.workingDirectory(),
                all.sdkHome(), all.sourceEncoding(), all.jmods(), all.sources(), all.classpath(),
                List.of("com.example.part."), false, false, all.moduleSourceSets());
        InputConfiguration configuration = new InputConfigurationAssembler().build(restricted);

        // ignored on this path until the MCP server's "analyze part of a project" needed it
        for (String name : List.of("a/main", "b/main", "b/test")) {
            SourceSet set = named(configuration, name);
            assertEquals(Set.of("com.example.part."), set.restrictToPackages(), name);
            assertTrue(set.acceptSource("com.example.part.x", "X"), name);
            assertFalse(set.acceptSource("com.example.other", "Y"), name);
        }
        assertTrue(named(new InputConfigurationAssembler().build(all), "a/main")
                .acceptSource("com.example.other", "Y"), "no restriction: every package");
    }
}
