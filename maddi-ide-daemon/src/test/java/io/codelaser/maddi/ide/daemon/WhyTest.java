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

    private static final String HOLDER = """
            package x;
            import java.util.ArrayList;
            import java.util.List;
            public class Holder {
                private final List<String> items = new ArrayList<>();
                List<String> items() {
                    return items;
                }
            }
            """;

    @DisplayName("@Dependent comes with its why: the return, then the mutable field")
    @Test
    public void dependentWhy(@TempDir Path projectDir) throws Exception {
        DaemonProtocol.Result result = DaemonAnalysisFixture.analyze(projectDir, "x/Holder.java", HOLDER, false, null);

        DaemonProtocol.ElementAnnotation items = method(result, "x.Holder.items(");
        assertTrue(items.displayAnnotations().contains("@Dependent"), items.displayAnnotations()::toString);
        DaemonProtocol.Why why = items.whys().stream().filter(w -> "Dependent".equals(w.annotation()))
                .findFirst().orElseThrow(() -> new AssertionError(items.toString()));
        assertEquals(List.of("7 returns this.items", "5 field items is mutable (List<String>)"),
                why.steps().stream().map(st -> st.line() + " " + st.text()).toList());
    }

    private static final String MAYBE = """
            package x;
            public class Maybe {
                String find(int k) {
                    if (k > 0) return null;
                    return "x";
                }
            }
            """;

    @DisplayName("@Nullable comes with its why: the null and where it is")
    @Test
    public void nullableWhy(@TempDir Path projectDir) throws Exception {
        DaemonProtocol.Result result = DaemonAnalysisFixture.analyze(projectDir, "x/Maybe.java", MAYBE, false, null);

        DaemonProtocol.ElementAnnotation find = method(result, "x.Maybe.find(");
        assertTrue(find.displayAnnotations().contains("@Nullable"), find.displayAnnotations()::toString);
        DaemonProtocol.Why why = find.whys().stream().filter(w -> "Nullable".equals(w.annotation()))
                .findFirst().orElseThrow(() -> new AssertionError(find.toString()));
        assertEquals(List.of("4 return null; (null in x.Maybe.find(int))"),
                why.steps().stream().map(st -> st.line() + " " + st.text()).toList());
    }

    @DisplayName("a type's level comes with its why: @Mutable at the assignable field, @Dependent at the return")
    @Test
    public void typeWhy(@TempDir Path bagDir, @TempDir Path holderDir) throws Exception {
        DaemonProtocol.ElementAnnotation bag = type(DaemonAnalysisFixture.analyze(bagDir, "x/Bag.java", BAG, false,
                null), "x.Bag");
        assertTrue(bag.displayAnnotations().contains("@Mutable"), bag.displayAnnotations()::toString);
        assertEquals(List.of("6 field count is assigned after construction (rule 0: all fields effectively final)",
                        "14 assigned in bump(..): this.count=this.count+1"), steps(bag, "Mutable"));

        DaemonProtocol.ElementAnnotation holder = type(DaemonAnalysisFixture.analyze(holderDir, "x/Holder.java",
                HOLDER, false, null), "x.Holder");
        assertTrue(holder.displayAnnotations().contains("@FinalFields"), holder.displayAnnotations()::toString);
        assertEquals(List.of("5 field items is dependent: exposed to, or shared with, the outside world (rule 3)",
                "7 Method 'items' returns field 'items': the caller can modify it"), steps(holder, "FinalFields"));
    }

    private static final String CONTAINERS = """
            package x;
            import java.util.List;
            public class C {
                public static final class Sink {
                    public void fill(List<String> list) { list.add("x"); }
                    public int size(List<String> list) { return list.size(); }
                }
                public static final class Many {
                    public void a(List<String> a) { a.clear(); }
                    public void b(List<String> b) { b.clear(); }
                    public void c(List<String> c) { c.clear(); }
                }
            }
            """;

    @DisplayName("why a type is not @Container: a negative why, only for a type close to one (2 parameters, default)")
    @Test
    public void notContainer(@TempDir Path projectDir) throws Exception {
        DaemonProtocol.Result result = DaemonAnalysisFixture.analyze(projectDir, "x/C.java", CONTAINERS, false, null);
        DaemonProtocol.ElementAnnotation sink = type(result, "x.C.Sink");
        DaemonProtocol.Why why = sink.whys().stream().filter(w -> "Container".equals(w.annotation())).findFirst()
                .orElseThrow(() -> new AssertionError(sink.toString()));
        assertTrue(why.negates());
        assertEquals(List.of("5 parameter list of fill(..) is modified: calls list.add(..), a modifying method"
                             + " outside the analysis"), steps(sink, "Container"));
        assertTrue(sink.properties().keySet().stream().noneMatch(k -> k.endsWith("Culprit")),
                sink.properties()::toString);
        // three modified parameters: past the limit, no explanation
        DaemonProtocol.ElementAnnotation many = type(result, "x.C.Many");
        assertTrue(many.whys().stream().noneMatch(w -> "Container".equals(w.annotation())), many::toString);
    }

    private static List<String> steps(DaemonProtocol.ElementAnnotation element, String annotation) {
        DaemonProtocol.Why why = element.whys().stream().filter(w -> annotation.equals(w.annotation())).findFirst()
                .orElseThrow(() -> new AssertionError(element.toString()));
        return why.steps().stream().map(st -> st.line() + " " + st.text()).toList();
    }

    private static DaemonProtocol.ElementAnnotation type(DaemonProtocol.Result result, String fqn) {
        return result.elementAnnotations().stream().filter(e -> "TYPE".equals(e.kind()) && e.fqn().equals(fqn))
                .findFirst().orElseThrow(() -> new AssertionError(fqn));
    }

    private static DaemonProtocol.ElementAnnotation method(DaemonProtocol.Result result, String fqnPrefix) {
        return result.elementAnnotations().stream()
                .filter(e -> "METHOD".equals(e.kind()) && e.fqn().startsWith(fqnPrefix))
                .findFirst().orElseThrow(() -> new AssertionError(fqnPrefix));
    }
}
