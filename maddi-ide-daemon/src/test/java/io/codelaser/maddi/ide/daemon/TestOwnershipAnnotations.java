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

import static io.codelaser.maddi.ide.daemon.DaemonAnalysisFixture.analyzeInWorld;
import static io.codelaser.maddi.ide.daemon.DaemonAnalysisFixture.annotationsFor;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ownership pass's annotations reach the IDE (road-to-immutability §055): {@code @Fresh}, {@code @Borrowed},
 * {@code @Owned} are POSITIVE; a borrowed argument of immutable type is a context default; a public constructor's
 * handed-over list is owned only when the front-end says the project is an application.
 */
public class TestOwnershipAnnotations {

    private static final String SOURCE = """
            package x;
            import java.util.ArrayList;
            import java.util.List;
            public class X {
                public static final class Lines {
                    private final List<String> lines = new ArrayList<>();
                    public List<String> copy() { return new ArrayList<>(lines); }
                    public int countEmpty(List<String> list) {
                        int n = 0;
                        for (String s : list) if (s.isEmpty()) n++;
                        return n;
                    }
                    public boolean has(String s) { return lines.contains(s); }
                }
                public static final class Palette {
                    private final List<String> colors;
                    public Palette(List<String> colors) { this.colors = colors; }
                    public static Palette of(String s) {
                        List<String> list = new ArrayList<>();
                        list.add(s);
                        return new Palette(list);
                    }
                    public String color(int i) { return colors.get(i); }
                }
            }
            """;

    private static DaemonProtocol.Annotation one(List<DaemonProtocol.Annotation> anns, String prefix) {
        List<DaemonProtocol.Annotation> found = anns.stream().filter(a -> a.text().startsWith(prefix)).toList();
        assertEquals(1, found.size(), "exactly one annotation starting with '" + prefix + "' in " + anns);
        return found.getFirst();
    }

    private static boolean none(List<DaemonProtocol.Annotation> anns, String prefix) {
        return anns.stream().noneMatch(a -> a.text().startsWith(prefix));
    }

    @DisplayName("@Fresh, @Borrowed and @Owned are shown as POSITIVE; a borrowed String is a context default")
    @Test
    public void openWorld(@TempDir Path dir) throws Exception {
        DaemonProtocol.Result r = analyzeInWorld(dir, "x/X.java", SOURCE, false);
        assertEquals(0, r.parseErrorCount(), "unexpected parse errors");

        DaemonProtocol.Annotation fresh = one(annotationsFor(r, "METHOD", "Lines.copy"), "@Fresh");
        assertEquals(AnnotationTagger.POSITIVE, fresh.polarity());
        assertFalse(fresh.contextDefault());

        DaemonProtocol.Annotation borrowed = one(annotationsFor(r, "PARAMETER", "Lines.countEmpty"), "@Borrowed");
        assertEquals(AnnotationTagger.POSITIVE, borrowed.polarity());
        assertFalse(borrowed.contextDefault(), "a borrowed List is worth showing");

        DaemonProtocol.Annotation borrowedString = one(annotationsFor(r, "PARAMETER", "Lines.has"), "@Borrowed");
        assertTrue(borrowedString.contextDefault(), "a borrowed String says little: " + borrowedString);

        DaemonProtocol.Annotation owned = one(annotationsFor(r, "FIELD", "Lines.lines"), "@Owned");
        assertEquals(AnnotationTagger.POSITIVE, owned.polarity());

        // a public constructor: in an open world the caller may keep the list
        assertTrue(none(annotationsFor(r, "FIELD", "Palette.colors"), "@Owned"),
                annotationsFor(r, "FIELD", "Palette.colors").toString());
    }

    @DisplayName("closed world: the public constructor's handed-over list is @Owned, and the type immutable")
    @Test
    public void closedWorld(@TempDir Path dir) throws Exception {
        DaemonProtocol.Result r = analyzeInWorld(dir, "x/X.java", SOURCE, true);
        assertEquals(0, r.parseErrorCount(), "unexpected parse errors");
        one(annotationsFor(r, "FIELD", "Palette.colors"), "@Owned");
        List<DaemonProtocol.Annotation> type = r.elementAnnotations().stream()
                .filter(e -> "TYPE".equals(e.kind()) && "x.X.Palette".equals(e.fqn()))
                .flatMap(e -> e.annotations().stream()).toList();
        assertFalse(none(type, "@Immutable"), type.toString());
    }
}
