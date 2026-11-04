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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 What the editor shows on a library file (Set.java from the JDK's sources): the last analysis's values for the
 library's elements, matched by signature, each saying whether our annotated API states it or it is the default.
 */
public class LibraryAnnotationsTest {

    private static final String SOURCE = """
            package x;
            import java.util.HashSet;
            import java.util.Set;
            public class Names {
                private final Set<String> names = new HashSet<>();
                void add(String s) { names.add(s); }
                int count() { return names.size(); }
            }
            """;

    private static DaemonProtocol.LibraryElement element(DaemonProtocol.LibraryResult result, String kind,
                                                        String fqnStart, String signature) {
        return result.elements().stream()
                .filter(e -> kind.equals(e.kind()) && e.fqn().startsWith(fqnStart) && signature.equals(e.signature()))
                .findFirst().orElseThrow(() -> new AssertionError(kind + " " + fqnStart + " " + signature + " in "
                        + result.elements().stream().filter(e -> e.fqn().startsWith(fqnStart))
                        .map(e -> e.kind() + " " + e.signature()).toList()));
    }

    @DisplayName("a library type's elements, by signature, with where their values come from")
    @Test
    public void library(@TempDir Path projectDir) throws Exception {
        WarmAnalysisService service = new WarmAnalysisService();
        DaemonProtocol.LibraryAnnotations request = new DaemonProtocol.LibraryAnnotations("lib",
                List.of("java.util.Set", "java.lang.ref.Reference", "no.such.Type"));
        DaemonProtocol.LibraryResult before = service.libraryAnnotations(request);
        assertTrue(before.elements().isEmpty(), "no analysis yet, no model");
        assertEquals(request.types(), before.unknownTypes());

        DaemonAnalysisFixture.analyze(projectDir, "x/Names.java", SOURCE, service);
        DaemonProtocol.LibraryResult result = service.libraryAnnotations(request);
        assertEquals("lib", result.requestId());
        assertEquals(List.of("no.such.Type"), result.unknownTypes());

        DaemonProtocol.LibraryElement add = element(result, "METHOD", "java.util.Set.", "add(java.lang.Object)");
        assertTrue(add.displayAnnotations().contains("@Modified"), add::toString);
        assertEquals(DaemonProtocol.ORIGIN_ANNOTATED, add.origin(), add::toString);
        assertEquals(List.of(), add.assumed());
        assertEquals("java.util.Set", add.type());

        DaemonProtocol.LibraryElement size = element(result, "METHOD", "java.util.Set.", "size()");
        assertFalse(size.displayAnnotations().contains("@Modified"), size::toString);
        assertEquals(DaemonProtocol.ORIGIN_ANNOTATED, size.origin(), size::toString);

        // no annotation in the hints: modifying, and the element says that is the default
        DaemonProtocol.LibraryElement refersTo = element(result, "METHOD", "java.lang.ref.Reference.",
                "refersTo(java.lang.Object)");
        assertTrue(refersTo.displayAnnotations().contains("@Modified"), refersTo::toString);
        assertEquals(DaemonProtocol.ORIGIN_DEFAULT, refersTo.origin(), refersTo::toString);
        assertEquals(List.of("nonModifyingMethod"), refersTo.assumed());

        DaemonProtocol.LibraryElement set = element(result, "TYPE", "java.util.Set", "java.util.Set");
        assertFalse(set.displayAnnotations().isEmpty(), set::toString);
    }

    @DisplayName("a method's signature: erased parameter types, the leftmost bound for a type variable")
    @Test
    public void signatures(@TempDir Path projectDir) throws Exception {
        WarmAnalysisService service = new WarmAnalysisService();
        DaemonAnalysisFixture.analyze(projectDir, "x/Names.java", SOURCE, service);
        DaemonProtocol.LibraryResult result = service.libraryAnnotations(new DaemonProtocol.LibraryAnnotations("lib",
                List.of("java.util.Collections", "java.util.Map")));
        List<String> signatures = result.elements().stream().map(DaemonProtocol.LibraryElement::signature).toList();
        // <T extends Object & Comparable<? super T>> T max(Collection<? extends T>)
        assertTrue(signatures.contains("max(java.util.Collection)"), signatures::toString);
        assertTrue(signatures.contains("addAll(java.util.Collection,java.lang.Object[])"), signatures::toString);
        // a nested type comes with its enclosing one, dotted
        assertTrue(result.elements().stream().anyMatch(e -> "TYPE".equals(e.kind())
                && "java.util.Map.Entry".equals(e.signature()) && "java.util.Map.Entry".equals(e.type())),
                signatures::toString);
        assertTrue(signatures.contains("put(java.lang.Object,java.lang.Object)#1"), signatures::toString);
    }
}
