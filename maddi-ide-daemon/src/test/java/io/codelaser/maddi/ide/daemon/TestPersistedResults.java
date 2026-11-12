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

import static io.codelaser.maddi.ide.daemon.DaemonAnalysisFixture.analyzeAll;
import static io.codelaser.maddi.ide.daemon.DaemonAnalysisFixture.displayFor;
import static io.codelaser.maddi.ide.daemon.DaemonAnalysisFixture.finding;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * -Dmaddi.daemon.persist: a daemon started again answers a request whose inputs did not change with the result an
 * earlier daemon stored, equal to it in everything but the request id and the time taken.
 */
public class TestPersistedResults {

    private static final String BOX = """
            package x;
            public class Box {
                private int n;
                public int get() { return n; }
            }
            """;
    private static final String BOX_MODIFYING = """
            package x;
            public class Box {
                private int n;
                public int get() { return n++; }
            }
            """;
    private static final String BAD = """
            package x;
            import io.codelaser.maddi.annotation.Container;
            class Mutable {
                private int value;
                void set(int v) { this.value = v; }
            }
            @Container
            interface HasAdd {
                void add(Mutable m);
            }
            class BadImpl implements HasAdd {
                @Override
                public void add(Mutable m) { m.set(3); }
            }
            """;

    private static void assertSameResult(DaemonProtocol.Result expected, DaemonProtocol.Result actual) {
        assertEquals(expected.elementAnnotations(), actual.elementAnnotations());
        assertEquals(expected.findings(), actual.findings());
        assertEquals(expected.initializationProblems(), actual.initializationProblems());
        assertEquals(expected.parseErrorCount(), actual.parseErrorCount());
        assertEquals(expected.hintsLoaded(), actual.hintsLoaded());
        assertEquals(expected.outcome(), actual.outcome());
    }

    @DisplayName("a daemon started again returns the stored result while the sources do not change")
    @Test
    public void restart(@TempDir Path projectDir, @TempDir Path storeDir) throws Exception {
        WarmAnalysisService first = new WarmAnalysisService(null, false, new ResultStore(storeDir));
        DaemonProtocol.Result cold = analyzeAll(projectDir, Map.of("x/Box.java", BOX, "x/Bad.java", BAD), first,
                false);
        assertFalse(first.lastResultRestored());
        assertEquals(DaemonProtocol.OUTCOME_CERTIFIED, cold.outcome());
        assertTrue(finding(cold, "contract-violation").isPresent());
        try (var files = Files.list(storeDir)) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".json")).count());
        }

        // a new daemon, the same store
        WarmAnalysisService second = new WarmAnalysisService(null, false, new ResultStore(storeDir));
        DaemonProtocol.Result restored = analyzeAll(projectDir, Map.of(), second, false);
        assertTrue(second.lastResultRestored());
        assertSameResult(cold, restored);

        // the model, built when asked for
        DaemonProtocol.LibraryResult library = second.libraryAnnotations(
                new DaemonProtocol.LibraryAnnotations("lib", List.of("java.util.Set")));
        assertEquals(List.of(), library.unknownTypes());
        assertFalse(library.elements().isEmpty());

        // cold: the store is skipped
        WarmAnalysisService third = new WarmAnalysisService(null, false, new ResultStore(storeDir));
        DaemonProtocol.Result fresh = analyzeAll(projectDir, Map.of(), third, false, true);
        assertFalse(third.lastResultRestored());
        assertSameResult(cold, fresh);
    }

    @DisplayName("an edit, or another setting, makes the stored result miss")
    @Test
    public void edit(@TempDir Path projectDir, @TempDir Path otherDir, @TempDir Path storeDir) throws Exception {
        ResultStore store = new ResultStore(storeDir);
        analyzeAll(projectDir, Map.of("x/Box.java", BOX, "x/Bad.java", BAD),
                new WarmAnalysisService(null, false, store), false);

        WarmAnalysisService afterEdit = new WarmAnalysisService(null, false, new ResultStore(storeDir));
        DaemonProtocol.Result edited = analyzeAll(projectDir, Map.of("x/Box.java", BOX_MODIFYING), afterEdit, false);
        assertFalse(afterEdit.lastResultRestored());
        assertTrue(displayFor(edited, "METHOD", "Box.get").contains("@Modified"),
                () -> "Box.get: " + displayFor(edited, "METHOD", "Box.get"));

        // the edited result is the one stored now
        WarmAnalysisService again = new WarmAnalysisService(null, false, new ResultStore(storeDir));
        DaemonProtocol.Result restored = analyzeAll(projectDir, Map.of(), again, false);
        assertTrue(again.lastResultRestored());
        assertSameResult(edited, restored);

        // another setting of the service: another entry
        WarmAnalysisService otherSetting = new WarmAnalysisService(true, false, new ResultStore(storeDir));
        analyzeAll(projectDir, Map.of(), otherSetting, false);
        assertFalse(otherSetting.lastResultRestored());

        // another project with the same sources: another configuration, another entry
        WarmAnalysisService otherProject = new WarmAnalysisService(null, false, new ResultStore(storeDir));
        analyzeAll(otherDir, Map.of("x/Box.java", BOX_MODIFYING, "x/Bad.java", BAD), otherProject, false);
        assertFalse(otherProject.lastResultRestored());
    }

    @DisplayName("the key: sources and class directories by content, jars by size and time")
    @Test
    public void key(@TempDir Path projectDir, @TempDir Path storeDir) throws Exception {
        Path src = projectDir.resolve("src/x/A.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package x; class A {}");
        Path classes = projectDir.resolve("classes/x/B.class");
        Files.createDirectories(classes.getParent());
        Files.writeString(classes, "one");
        DaemonProtocol.AnalyzeConfig config = new DaemonProtocol.AnalyzeConfig(projectDir.toString(),
                System.getProperty("java.home"), "UTF-8", List.of("java.base"),
                List.of(new DaemonProtocol.SourceRoot("main", "src", false)),
                List.of(new DaemonProtocol.ClasspathEntry("classes", "compile"),
                        new DaemonProtocol.ClasspathEntry("jmod:java.base", "compile")),
                List.of(), false, false);
        ResultStore store = new ResultStore(storeDir);
        ResultStore.Key k1 = store.key(config);
        assertEquals(k1, store.key(config), "stable");

        Files.writeString(classes, "two");
        ResultStore.Key k2 = store.key(config);
        assertNotEquals(k1.classPath(), k2.classPath());
        assertEquals(k1.sources(), k2.sources());
        assertEquals(k1.engine(), k2.engine());

        Files.writeString(src, "package x; class A { }");
        ResultStore.Key k3 = store.key(config);
        assertNotEquals(k2.sources(), k3.sources());
        assertEquals(k2.classPath(), k3.classPath());

        // a file added is a change too
        Files.writeString(projectDir.resolve("src/x/C.java"), "package x; class C {}");
        assertNotEquals(k3.sources(), store.key(config).sources());
    }
}
