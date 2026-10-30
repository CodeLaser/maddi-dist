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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static io.codelaser.maddi.ide.daemon.DaemonAnalysisFixture.analyze;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A project element carries its name as a library element does (declaring type, signature), so an editor can put its
 * values back on the declaration once an edit has moved it; the editor computes the same key from its own syntax tree.
 */
public class ElementKeyTest {

    private static final String SOURCE = """
            package x;
            import java.util.ArrayList;
            import java.util.List;
            public class Bag {
                private final List<String> items = new ArrayList<>();
                public void add(String item) { items.add(item); }
                public int size() { return items.size(); }
                static class Inner {
                    int[] values(List<String>[] lists, String... more) { return null; }
                }
            }
            """;

    private static DaemonProtocol.ElementAnnotation element(DaemonProtocol.Result r, String kind, String fqn) {
        return r.elementAnnotations().stream().filter(e -> kind.equals(e.kind()) && fqn.equals(e.fqn()))
                .findFirst().orElseThrow(() -> new AssertionError(kind + " " + fqn + " not in "
                        + r.elementAnnotations().stream().map(DaemonProtocol.ElementAnnotation::fqn).toList()));
    }

    @DisplayName("type, field, method and parameter are named by declaring type and signature")
    @Test
    public void test(@TempDir Path dir) throws Exception {
        DaemonProtocol.Result r = analyze(dir, "x/Bag.java", SOURCE);
        assertEquals(0, r.parseErrorCount(), "unexpected parse errors");

        DaemonProtocol.ElementAnnotation type = element(r, "TYPE", "x.Bag");
        assertEquals("x.Bag", type.owner());
        assertEquals("x.Bag", type.signature());

        DaemonProtocol.ElementAnnotation field = element(r, "FIELD", "x.Bag.items");
        assertEquals("x.Bag", field.owner());
        assertEquals("items", field.signature());

        DaemonProtocol.ElementAnnotation add = r.elementAnnotations().stream()
                .filter(e -> "METHOD".equals(e.kind()) && e.fqn().startsWith("x.Bag.add(")).findFirst().orElseThrow();
        assertEquals("x.Bag", add.owner());
        assertEquals("add(java.lang.String)", add.signature());

        DaemonProtocol.ElementAnnotation values = r.elementAnnotations().stream()
                .filter(e -> "METHOD".equals(e.kind()) && e.fqn().startsWith("x.Bag.Inner.values("))
                .findFirst().orElseThrow();
        // nested types with dots, arrays and varargs as the arrays they are: as the editor's erasure prints them
        assertEquals("x.Bag.Inner", values.owner());
        assertEquals("values(java.util.List[],java.lang.String[])", values.signature());

        r.elementAnnotations().stream().filter(e -> "PARAMETER".equals(e.kind()) && e.fqn().contains("add("))
                .findFirst().ifPresent(p -> assertEquals("add(java.lang.String)#0", p.signature()));
    }
}
