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

package io.codelaser.maddi.ide.eclipse.tests;

import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.ide.eclipse.MaddiLibrary;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jface.text.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A library file's declarations get the daemon's library elements by declaring type, kind and signature; the
 * signature is computed from the AST's bindings as the daemon computes it from the analysis.
 */
public class MaddiLibraryTest {

    private static final String SOURCE = """
            package lib;
            import java.util.Collection;
            public interface Bag<E> {
                boolean add(E e);
                int size();
                static <T extends Object & Comparable<? super T>> T max(Collection<? extends T> c) { return null; }
                static <T> boolean addAll(Collection<? super T> c, T... elements) { return false; }
                interface Entry<K, V> {
                    V setValue(V value);
                }
            }
            """;

    private static CompilationUnit parse() {
        return parse(SOURCE, "/lib/Bag.java");
    }

    private static CompilationUnit parse(String source, String unitName) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        parser.setUnitName(unitName);
        parser.setEnvironment(null, null, null, true);
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.VERSION_21, options);
        parser.setCompilerOptions(options);
        parser.setResolveBindings(true);
        return (CompilationUnit) parser.createAST(null);
    }

    private static AnalysisModel.LibraryElement element(String type, String kind, String signature, String origin) {
        return new AnalysisModel.LibraryElement(kind, type, type + "." + signature, signature, origin,
                AnalysisModel.ORIGIN_DEFAULT.equals(origin) ? List.of("nonModifyingMethod") : List.of(),
                List.of("@Modified"), List.of(), Map.of());
    }

    @Test
    @DisplayName("a method's signature: erased parameter types, the leftmost bound, varargs as an array")
    public void signatures() {
        TypeDeclaration bag = (TypeDeclaration) parse().types().getFirst();
        List<String> signatures = Arrays.stream(bag.getMethods())
                .map(MethodDeclaration::resolveBinding).map(MaddiLibrary::signature).toList();
        assertEquals(List.of("add(java.lang.Object)", "size()", "max(java.util.Collection)",
                "addAll(java.util.Collection,java.lang.Object[])"), signatures);
    }

    @Test
    @DisplayName("elements land on the names they match, nested types included, with the note on their origin")
    public void placement() {
        List<AnalysisModel.ElementAnnotation> placed = MaddiLibrary.place(parse(), new Document(SOURCE), "lib/Bag",
                List.of(element("lib.Bag", "METHOD", "add(java.lang.Object)", AnalysisModel.ORIGIN_ANNOTATED),
                        element("lib.Bag", "METHOD", "size()", AnalysisModel.ORIGIN_DEFAULT),
                        element("lib.Bag.Entry", "METHOD", "setValue(java.lang.Object)",
                                AnalysisModel.ORIGIN_COMPUTED),
                        element("lib.Bag", "PARAMETER", "addAll(java.util.Collection,java.lang.Object[])#1",
                                AnalysisModel.ORIGIN_ANNOTATED),
                        // not in the file: placed nowhere
                        element("lib.Bag", "METHOD", "clear()", AnalysisModel.ORIGIN_ANNOTATED)));
        assertEquals(4, placed.size());
        AnalysisModel.ElementAnnotation add = placed.getFirst();
        // on the method's name, 1-based, the end column inclusive
        assertEquals(List.of(4, 13, 4, 15), List.of(add.beginLine(), add.beginCol(), add.endLine(), add.endCol()));
        assertEquals("Manually annotated, in maddi's annotated API", add.properties().get(AnalysisModel.LIBRARY_NOTE));
        AnalysisModel.ElementAnnotation size = placed.get(1);
        assertEquals("No annotation in maddi's annotated API: @Modified is the default",
                size.properties().get(AnalysisModel.LIBRARY_NOTE));
        assertEquals(9, placed.stream().filter(e -> e.fqn().contains("setValue")).findFirst().orElseThrow()
                .beginLine());
        assertEquals("PARAMETER", placed.stream().filter(e -> e.fqn().contains("#1")).findFirst().orElseThrow()
                .kind());
    }

    /*
     A project file's annotations follow their declarations when an edit moves them. Recorded against the source with
     the class's brace on its first line (the field on line 3), shown on SPLIT, where the brace has its own line: the
     field's name is on line 4 now, and its hints must go with it, as the methods' must.
     */
    private static final String SPLIT = """
            package p;
            public class Bag
            {
                private final Object items = null;
                public void add(String item) { }
                public void gone() { }
            }
            """;

    private static AnalysisModel.ElementAnnotation recorded(String kind, String owner, String signature, int line,
                                                            int col, int length) {
        return new AnalysisModel.ElementAnnotation("/p/Bag.java", line, col, line, col + length - 1, kind,
                owner + "." + signature, List.of("@Modified"), List.of(), Map.of(), List.of(), owner, signature);
    }

    @Test
    @DisplayName("a project file's annotations are placed on their declarations by name, not by the old position")
    public void follow() {
        Document document = new Document(SPLIT);
        List<AnalysisModel.ElementAnnotation> shown = MaddiLibrary.follow(parse(SPLIT, "/p/Bag.java"), document, List.of(
                recorded("TYPE", "p.Bag", "p.Bag", 2, 14, 3),
                recorded("FIELD", "p.Bag", "items", 3, 26, 5),
                recorded("METHOD", "p.Bag", "add(java.lang.String)", 4, 17, 3),
                recorded("METHOD", "p.Bag", "clear()", 5, 17, 5),
                // an older daemon's: no name, so it keeps its position
                new AnalysisModel.ElementAnnotation("/p/Bag.java", 6, 17, 6, 20, "METHOD", "p.Bag.gone()",
                        List.of("@NotModified"), List.of(), Map.of())));

        assertEquals(4, shown.size(), shown.toString());
        assertFalse(shown.stream().anyMatch(a -> a.fqn().endsWith("clear()")), "a declaration that is gone");
        assertEquals(6, find(shown, "gone()").beginLine());
        assertEquals(2, find(shown, "p.Bag.p.Bag").beginLine());
        AnalysisModel.ElementAnnotation items = find(shown, "items");
        assertEquals(4, items.beginLine());
        assertEquals(26, items.beginCol());
        assertEquals(30, items.endCol());
        assertEquals(5, find(shown, "add(java.lang.String)").beginLine());
    }

    private static AnalysisModel.ElementAnnotation find(List<AnalysisModel.ElementAnnotation> annotations,
                                                        String fqnSuffix) {
        return annotations.stream().filter(a -> a.fqn().endsWith(fqnSuffix)).findFirst().orElseThrow();
    }
}
