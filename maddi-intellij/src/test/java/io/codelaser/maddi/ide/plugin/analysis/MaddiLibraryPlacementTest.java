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

package io.codelaser.maddi.ide.plugin.analysis;

import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import io.codelaser.maddi.ide.client.AnalysisModel;

import java.util.List;
import java.util.Map;

/*
 A library file's declarations get the daemon's library elements by declaring type, kind and signature; the signature
 is computed from the declaration on screen as the daemon computes it from the analysis. (The light fixture has no
 JDK: the parameter types are the file's own.)
 */
public class MaddiLibraryPlacementTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String SOURCE = """
            package lib;
            public interface Bag<E> {
                boolean add(E e);
                int size();
                static <T extends Object & Comparable<? super T>> T max(Entry<? extends T, ?> c) { return null; }
                static <T> boolean addAll(Entry<? super T, ?> c, T... elements) { return false; }
                interface Entry<K, V> {
                    V setValue(V value);
                }
            }
            """;

    private static AnalysisModel.LibraryElement element(String type, String kind, String signature, String origin,
                                                        String... annotations) {
        return new AnalysisModel.LibraryElement(kind, type, type + "." + signature, signature, origin,
                AnalysisModel.ORIGIN_DEFAULT.equals(origin) ? List.of("nonModifyingMethod") : List.of(),
                List.of(annotations), List.of(), Map.of());
    }

    public void testSignatures() {
        PsiJavaFile file = (PsiJavaFile) myFixture.configureByText("Bag.java", SOURCE);
        List<String> signatures = java.util.Arrays.stream(file.getClasses()[0].getMethods())
                .map(MaddiLibraryPlacement::signature).toList();
        assertEquals(List.of("add(java.lang.Object)", "size()", "max(lib.Bag.Entry)",
                "addAll(lib.Bag.Entry,java.lang.Object[])"), signatures);
        assertEquals(List.of("lib.Bag"), MaddiLibraryPlacement.typesOf(file));
    }

    public void testPlacement() {
        PsiJavaFile file = (PsiJavaFile) myFixture.configureByText("Bag.java", SOURCE);
        Document doc = myFixture.getEditor().getDocument();
        List<AnalysisModel.ElementAnnotation> placed = MaddiLibraryPlacement.place(file, doc, "/lib/Bag.java", List.of(
                element("lib.Bag", "TYPE", "lib.Bag", AnalysisModel.ORIGIN_ANNOTATED, "@Container"),
                element("lib.Bag", "METHOD", "add(java.lang.Object)", AnalysisModel.ORIGIN_ANNOTATED, "@Modified"),
                element("lib.Bag", "METHOD", "size()", AnalysisModel.ORIGIN_DEFAULT, "@Modified"),
                element("lib.Bag.Entry", "METHOD", "setValue(java.lang.Object)", AnalysisModel.ORIGIN_COMPUTED,
                        "@Modified"),
                // not in the file: placed nowhere
                element("lib.Bag", "METHOD", "clear()", AnalysisModel.ORIGIN_ANNOTATED, "@Modified")));
        assertEquals(4, placed.size());

        AnalysisModel.ElementAnnotation add = placed.stream().filter(a -> a.fqn().endsWith("add(java.lang.Object)"))
                .findFirst().orElseThrow();
        // on the method's name, 1-based, the end column inclusive
        assertEquals(Integer.valueOf(3), add.beginLine());
        assertEquals(Integer.valueOf(13), add.beginCol());
        assertEquals(Integer.valueOf(15), add.endCol());
        assertEquals("Manually annotated, in maddi's annotated API", add.properties().get(AnalysisModel.LIBRARY_NOTE));

        AnalysisModel.ElementAnnotation size = placed.stream().filter(a -> a.fqn().endsWith("size()"))
                .findFirst().orElseThrow();
        assertEquals("No annotation in maddi's annotated API: @Modified is the default",
                size.properties().get(AnalysisModel.LIBRARY_NOTE));

        AnalysisModel.ElementAnnotation setValue = placed.stream().filter(a -> a.fqn().contains("setValue"))
                .findFirst().orElseThrow();
        assertEquals(Integer.valueOf(8), setValue.beginLine());
        assertEquals("Not in maddi's annotated API: the analysis's defaults for a library",
                setValue.properties().get(AnalysisModel.LIBRARY_NOTE));
    }
}
