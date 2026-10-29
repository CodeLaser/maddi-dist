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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daemon asks the analysis for culprits, and hands them to the IDE as the "why" of an annotation: for
 * {@code @StaticSideEffects}, the first statement modifying another type's static state, followed into the callee.
 */
public class WhyTest {

    private static final String SOURCE = """
            package x;
            public class Big {
                void big(String s) {
                    int n = 1;
                    Registry.register(s);
                }
            }
            class Registry {
                static int count;
                static void register(String s) {
                    count = count + s.length();
                }
            }
            """;

    @DisplayName("@StaticSideEffects comes with its why: the call, then the assignment in the callee")
    @Test
    public void staticSideEffectsWhy(@TempDir Path projectDir) throws Exception {
        DaemonProtocol.Result result = DaemonAnalysisFixture.analyze(projectDir, "x/Big.java", SOURCE, false, null);

        DaemonProtocol.ElementAnnotation big = result.elementAnnotations().stream()
                .filter(e -> "METHOD".equals(e.kind()) && e.fqn().startsWith("x.Big.big("))
                .findFirst().orElseThrow();
        assertTrue(big.displayAnnotations().contains("@StaticSideEffects"), big.displayAnnotations()::toString);
        assertEquals(1, big.whys().size(), big::toString);
        DaemonProtocol.Why why = big.whys().getFirst();
        assertEquals("StaticSideEffects", why.annotation());
        assertEquals(2, why.steps().size(), why::toString);
        assertEquals(5, why.steps().get(0).line());
        assertEquals("calls Registry.register(..)", why.steps().get(0).text());
        assertEquals(11, why.steps().get(1).line());
        assertEquals("assigns Registry.count", why.steps().get(1).text());
        assertTrue(why.steps().get(0).uri().endsWith("x/Big.java"), why.steps().get(0).uri());
        // the culprit is the why, not one more property
        assertTrue(big.properties().keySet().stream().noneMatch(k -> k.toLowerCase().contains("culprit")),
                big.properties()::toString);
    }

    private static final String BAG = """
            package x;
            import java.util.ArrayList;
            import java.util.List;
            public class Bag {
                private final List<String> items = new ArrayList<>();
                private int count;
                void add(String s) {
                    int n = s.length();
                    store(s);
                }
                private void store(String s) {
                    items.add(s);
                }
                void bump() { count = count + 1; }
                int size() { return items.size(); }
            }
            """;

    @DisplayName("@Modified comes with its why: the call into the helper, then the library call that modifies")
    @Test
    public void modifiedWhy(@TempDir Path projectDir) throws Exception {
        DaemonProtocol.Result result = DaemonAnalysisFixture.analyze(projectDir, "x/Bag.java", BAG, false, null);

        DaemonProtocol.ElementAnnotation add = method(result, "x.Bag.add(");
        assertTrue(add.displayAnnotations().contains("@Modified"), add.displayAnnotations()::toString);
        DaemonProtocol.Why why = add.whys().stream().filter(w -> "Modified".equals(w.annotation()))
                .findFirst().orElseThrow(() -> new AssertionError(add.toString()));
        assertEquals(List.of("9 calls store(..)", "12 calls items.add(..), a modifying method outside the analysis"),
                why.steps().stream().map(st -> st.line() + " " + st.text()).toList());
        assertEquals(List.of("14 assigns this.count"), method(result, "x.Bag.bump(").whys().getFirst().steps()
                .stream().map(st -> st.line() + " " + st.text()).toList());
        DaemonProtocol.ElementAnnotation size = method(result, "x.Bag.size(");
        assertTrue(size.whys().isEmpty(), size::toString);
    }

    private static DaemonProtocol.ElementAnnotation method(DaemonProtocol.Result result, String fqnPrefix) {
        return result.elementAnnotations().stream()
                .filter(e -> "METHOD".equals(e.kind()) && e.fqn().startsWith(fqnPrefix))
                .findFirst().orElseThrow(() -> new AssertionError(fqnPrefix));
    }
}
