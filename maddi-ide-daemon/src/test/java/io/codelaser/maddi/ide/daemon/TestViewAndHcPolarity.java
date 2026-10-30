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
import java.util.List;

import static io.codelaser.maddi.ide.daemon.DaemonAnalysisFixture.analyze;
import static io.codelaser.maddi.ide.daemon.DaemonAnalysisFixture.annotationsFor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Two verdicts the decorator learned after the tagger was written: {@code @UnmodifiableView} (maddi-mod#52) and
 * {@code @Modified(hc=true)}, a modification of the hidden content only (maddi-mod#25). Both are proven guarantees,
 * so both are POSITIVE; the second must not appear beside the tagger's own baseline {@code @Modified}, and the first
 * is left out where the result is immutable anyway. And {@code @FinalFields}, which used to read {@code @Mutable};
 * on a type without instance fields it is the baseline.
 */
public class TestViewAndHcPolarity {

    private static final String SOURCE = """
            package x;
            import java.util.ArrayList;
            import java.util.Collections;
            import java.util.List;
            public class X {
                static class Item {
                    int n;
                    void touch() { n++; }
                }
                private final List<String> list = new ArrayList<>();
                private final List<Item> items = new ArrayList<>();
                public List<String> view() { return Collections.unmodifiableList(list); }
                public List<String> copy() { return new ArrayList<>(list); }
                // the elements are modified, the list is not
                public static void touchAll(List<Item> items) { for (Item i : items) i.touch(); }
                public static void add(List<Item> items, Item i) { items.add(i); }
                public void touchOwn() { for (Item i : items) i.touch(); }
                public void addOwn(Item i) { items.add(i); }
                // an immutable result: a view, but saying so adds nothing
                public String joined() { return String.join(",", list); }
                public void addString(String s) { list.add(s); }
                // no instance fields: all final, vacuously
                interface Sink {
                    void put(String s);
                }
            }
            """;

    private static List<DaemonProtocol.Annotation> starting(List<DaemonProtocol.Annotation> anns, String prefix) {
        return anns.stream().filter(a -> a.text().startsWith(prefix)).toList();
    }

    /** {@link DaemonAnalysisFixture#annotationsFor} matches on part of the name, which would take in nested types. */
    private static List<DaemonProtocol.Annotation> typeAnnotations(DaemonProtocol.Result r, String fqn) {
        return r.elementAnnotations().stream().filter(e -> "TYPE".equals(e.kind()) && fqn.equals(e.fqn()))
                .flatMap(e -> e.annotations().stream()).toList();
    }

    private static DaemonProtocol.Annotation one(List<DaemonProtocol.Annotation> anns, String prefix) {
        List<DaemonProtocol.Annotation> found = starting(anns, prefix);
        assertEquals(1, found.size(), "exactly one annotation starting with '" + prefix + "' in " + anns);
        return found.getFirst();
    }

    @DisplayName("@UnmodifiableView and @Modified(hc=true) are POSITIVE, and the latter is not doubled")
    @Test
    public void test(@TempDir Path dir) throws Exception {
        DaemonProtocol.Result r = analyze(dir, "x/X.java", SOURCE);
        assertEquals(0, r.parseErrorCount(), "unexpected parse errors");

        DaemonProtocol.Annotation view = one(annotationsFor(r, "METHOD", "X.view"), "@UnmodifiableView");
        assertEquals(AnnotationTagger.POSITIVE, view.polarity(), view.toString());
        assertTrue(starting(annotationsFor(r, "METHOD", "X.copy"), "@UnmodifiableView").isEmpty());
        assertTrue(starting(annotationsFor(r, "METHOD", "X.joined"), "@UnmodifiableView").isEmpty(),
                "an immutable result is no view: " + annotationsFor(r, "METHOD", "X.joined"));

        // all fields final, and modified: @FinalFields, not @Mutable, and shown by default
        List<DaemonProtocol.Annotation> type = typeAnnotations(r, "x.X");
        DaemonProtocol.Annotation finalFields = one(type, "@FinalFields");
        assertEquals(AnnotationTagger.POSITIVE, finalFields.polarity());
        assertFalse(finalFields.contextDefault());
        assertTrue(starting(type, "@Mutable").isEmpty(), type.toString());
        // Item has a non-final field: plain @Mutable
        assertEquals(1, starting(typeAnnotations(r, "x.X.Item"), "@Mutable").size());
        // an interface has no fields: its @FinalFields is the baseline, hidden by default as a class's @Mutable is
        DaemonProtocol.Annotation sink = one(typeAnnotations(r, "x.X.Sink"), "@FinalFields");
        assertTrue(sink.contextDefault(), sink.toString());

        // parameter: touchAll changes what the list holds, add changes the list
        DaemonProtocol.Annotation touched = one(annotationsFor(r, "PARAMETER", "X.touchAll"), "@Modified");
        assertEquals("@Modified(hc=true)", touched.text());
        assertEquals(AnnotationTagger.POSITIVE, touched.polarity());
        assertFalse(touched.contextDefault());
        DaemonProtocol.Annotation added = one(annotationsFor(r, "PARAMETER", "X.add(java.util.List"), "@Modified");
        assertEquals("@Modified", added.text());
        assertEquals(AnnotationTagger.NEGATIVE, added.polarity());

        // method: the same pair, on the object's own field
        DaemonProtocol.Annotation touchOwn = one(annotationsFor(r, "METHOD", "X.touchOwn"), "@Modified");
        assertEquals("@Modified(hc=true)", touchOwn.text());
        assertEquals(AnnotationTagger.POSITIVE, touchOwn.polarity());
        DaemonProtocol.Annotation addOwn = one(annotationsFor(r, "METHOD", "X.addOwn"), "@Modified");
        assertEquals("@Modified", addOwn.text());
        assertEquals(AnnotationTagger.NEGATIVE, addOwn.polarity());
    }
}
