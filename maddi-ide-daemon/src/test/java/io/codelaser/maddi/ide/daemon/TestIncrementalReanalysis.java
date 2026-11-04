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
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * -Dmaddi.daemon.warm: a second request with the same configuration re-analyses what changed on the state the first
 * left, and must say what a cold run of the same files says. The cold run is the oracle.
 */
public class TestIncrementalReanalysis {

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
    private static final String USER = """
            package x;
            public class User {
                int twice(Box b) { return b.get() * 2; }
            }
            """;
    // a contract violation in a type no edit touches: its finding must survive an incremental run
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

    /* every element's display annotations, by element */
    private static Map<String, List<String>> annotations(DaemonProtocol.Result r) {
        Map<String, List<String>> map = new TreeMap<>();
        r.elementAnnotations().forEach(e -> map.put(e.kind() + " " + e.fqn(),
                e.displayAnnotations().stream().sorted().toList()));
        return map;
    }

    private static List<String> findings(DaemonProtocol.Result r) {
        return r.findings().stream().map(f -> f.category() + " " + f.uri() + ":" + f.beginLine() + " " + f.message())
                .sorted().toList();
    }

    @DisplayName("an edit is re-analysed incrementally, and says what a cold run says")
    @Test
    public void editEqualsCold(@TempDir Path warmDir, @TempDir Path coldDir) throws Exception {
        WarmAnalysisService service = new WarmAnalysisService(null, true);
        DaemonProtocol.Result first = analyzeAll(warmDir, Map.of("x/Box.java", BOX, "x/User.java", USER,
                "x/Bad.java", BAD), service, false);
        assertNotEquals(DaemonProtocol.OUTCOME_INCREMENTAL, first.outcome());
        assertTrue(displayFor(first, "METHOD", "Box.get").contains("@NotModified"),
                () -> "Box.get: " + displayFor(first, "METHOD", "Box.get"));
        assertTrue(finding(first, "contract-violation").isPresent());

        DaemonProtocol.Result second = analyzeAll(warmDir, Map.of("x/Box.java", BOX_MODIFYING), service, false);
        assertEquals(DaemonProtocol.OUTCOME_INCREMENTAL, second.outcome());
        assertTrue(displayFor(second, "METHOD", "Box.get").contains("@Modified"),
                () -> "Box.get: " + displayFor(second, "METHOD", "Box.get"));
        assertTrue(finding(second, "contract-violation").isPresent(), "the untouched type keeps its finding");

        DaemonProtocol.Result cold = analyzeAll(coldDir, Map.of("x/Box.java", BOX_MODIFYING, "x/User.java", USER,
                "x/Bad.java", BAD), new WarmAnalysisService(null, false), false);
        assertEquals(annotations(cold).toString().replace(coldDir.toString(), "DIR"),
                annotations(second).toString().replace(warmDir.toString(), "DIR"));
        assertEquals(findings(cold).toString().replace(coldDir.toUri().toString(), "DIR/"),
                findings(second).toString().replace(warmDir.toUri().toString(), "DIR/"));

        // and back: the edit undone
        DaemonProtocol.Result third = analyzeAll(warmDir, Map.of("x/Box.java", BOX), service, false);
        assertEquals(DaemonProtocol.OUTCOME_INCREMENTAL, third.outcome());
        assertEquals(annotations(first), annotations(third));
        assertEquals(findings(first), findings(third));
    }

    @DisplayName("nothing changed: the previous result, at once")
    @Test
    public void noChange(@TempDir Path dir) throws Exception {
        WarmAnalysisService service = new WarmAnalysisService(null, true);
        DaemonProtocol.Result first = analyzeAll(dir, Map.of("x/Box.java", BOX, "x/User.java", USER), service, false);
        DaemonProtocol.Result again = analyzeAll(dir, Map.of(), service, false);
        assertEquals(first.outcome(), again.outcome());
        assertEquals(annotations(first), annotations(again));
        assertEquals(findings(first), findings(again));
    }

    @DisplayName("another configuration, or a file added: a cold run")
    @Test
    public void coldFallbacks(@TempDir Path dir) throws Exception {
        WarmAnalysisService service = new WarmAnalysisService(null, true);
        analyzeAll(dir, Map.of("x/Box.java", BOX, "x/User.java", USER), service, false);

        DaemonProtocol.Result otherConfig = analyzeAll(dir, Map.of("x/Box.java", BOX_MODIFYING), service, true);
        assertNotEquals(DaemonProtocol.OUTCOME_INCREMENTAL, otherConfig.outcome());

        DaemonProtocol.Result added = analyzeAll(dir, Map.of("x/Bad.java", BAD), service, true);
        assertNotEquals(DaemonProtocol.OUTCOME_INCREMENTAL, added.outcome());
        assertTrue(finding(added, "contract-violation").isPresent());

        Files.delete(dir.resolve("src/x/Bad.java"));
        DaemonProtocol.Result removed = analyzeAll(dir, Map.of(), service, true);
        assertNotEquals(DaemonProtocol.OUTCOME_INCREMENTAL, removed.outcome());
        assertTrue(finding(removed, "contract-violation").isEmpty());
    }

    @DisplayName("a cold request: from scratch, and the run after it is incremental again")
    @Test
    public void coldRequest(@TempDir Path dir) throws Exception {
        WarmAnalysisService service = new WarmAnalysisService(null, true);
        analyzeAll(dir, Map.of("x/Box.java", BOX, "x/User.java", USER), service, false);

        DaemonProtocol.Result cold = analyzeAll(dir, Map.of("x/Box.java", BOX_MODIFYING), service, false, true);
        assertNotEquals(DaemonProtocol.OUTCOME_INCREMENTAL, cold.outcome(), "the same configuration, yet cold");
        assertTrue(displayFor(cold, "METHOD", "Box.get").contains("@Modified"));

        DaemonProtocol.Result next = analyzeAll(dir, Map.of("x/Box.java", BOX), service, false, false);
        assertEquals(DaemonProtocol.OUTCOME_INCREMENTAL, next.outcome(), "the cold run left its state behind");
        assertTrue(displayFor(next, "METHOD", "Box.get").contains("@NotModified"));
    }
}
