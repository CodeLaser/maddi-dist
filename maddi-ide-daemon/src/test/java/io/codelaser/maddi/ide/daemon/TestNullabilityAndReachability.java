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

import static io.codelaser.maddi.ide.daemon.DaemonAnalysisFixture.analyze;
import static io.codelaser.maddi.ide.daemon.DaemonAnalysisFixture.annotationsFor;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The daemon runs the nullability pass and the reachability pass (MODREACH), as the CLI does: {@code @Nullable} is
 * shown, {@code @NotNull} hidden by default, a primitive gets neither; and a caller of an {@code @IgnoreModifications}
 * method that changes nothing else is {@code @NotModified}, which only the reachability pass decides.
 */
public class TestNullabilityAndReachability {

    private static final String SOURCE = """
            package x;
            import io.codelaser.maddi.annotation.rare.IgnoreModifications;
            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            public class X {
                private final Map<String, String> entries = new HashMap<>();
                private final List<String> buffer = new ArrayList<>();
                public void put(String k, String v) { entries.put(k, v); }
                public String find(String key) { return entries.get(key); }
                public int length(String s) { return s.length(); }
                public String describe(String s) { return s == null ? "(none)" : s; }
                @IgnoreModifications
                public void log(String s) { buffer.add(s); }
                public int twice(int x) { log("twice"); return 2 * x; }
            }
            """;

    private static List<DaemonProtocol.Annotation> starting(List<DaemonProtocol.Annotation> anns, String prefix) {
        return anns.stream().filter(a -> a.text().startsWith(prefix)).toList();
    }

    private static DaemonProtocol.Annotation one(List<DaemonProtocol.Annotation> anns, String text) {
        List<DaemonProtocol.Annotation> found = anns.stream().filter(a -> a.text().equals(text)).toList();
        assertEquals(1, found.size(), "exactly one " + text + " in " + anns);
        return found.getFirst();
    }

    @DisplayName("nullability verdicts reach the IDE; MODREACH decides the ignored modification")
    @Test
    public void test(@TempDir Path dir) throws Exception {
        DaemonProtocol.Result r = analyze(dir, "x/X.java", SOURCE);
        assertEquals(0, r.parseErrorCount(), "unexpected parse errors");

        DaemonProtocol.Annotation find = one(annotationsFor(r, "METHOD", "X.find"), "@Nullable");
        assertEquals(AnnotationTagger.NEGATIVE, find.polarity());
        assertFalse(find.contextDefault(), "@Nullable is the exception: shown by default");

        DaemonProtocol.Annotation describe = one(annotationsFor(r, "PARAMETER", "X.describe"), "@Nullable");
        assertFalse(describe.contextDefault());

        DaemonProtocol.Annotation length = one(annotationsFor(r, "PARAMETER", "X.length"), "@NotNull");
        assertEquals(AnnotationTagger.POSITIVE, length.polarity());
        assertTrue(length.contextDefault(), "@NotNull is the norm: hidden by default");
        assertTrue(starting(annotationsFor(r, "METHOD", "X.length"), "@N").stream()
                .noneMatch(a -> a.text().equals("@NotNull") || a.text().equals("@Nullable")), "an int has none");

        List<DaemonProtocol.Annotation> twice = annotationsFor(r, "METHOD", "X.twice");
        assertTrue(starting(twice, "@Modified").isEmpty(), "only the logging modifies: " + twice);
        one(twice, "@NotModified");
    }
}
