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

package io.codelaser.maddi.ide.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GutterTextTest {

    private static final String URI = "file:///p/src/x/Bag.java";

    @Test
    @DisplayName("each annotation's why under it, then a method's parameters with theirs")
    public void whysAndParameters() {
        AnalysisModel.ElementAnnotation add = new AnalysisModel.ElementAnnotation(URI, 7, 5, 10, 5, "METHOD",
                "x.Bag.add(String)", List.of("@Modified"), List.of(), Map.of(),
                List.of(new AnalysisModel.Why("Modified", List.of(
                        new AnalysisModel.WhyStep(URI, 9, 9, "calls store(..)"),
                        new AnalysisModel.WhyStep("file:///p/src/x/Store.java", 12, 9, "calls items.add(..)"),
                        new AnalysisModel.WhyStep(null, 0, 0, "a contract")))));
        AnalysisModel.ElementAnnotation s = new AnalysisModel.ElementAnnotation(URI, 7, 14, 7, 21, "PARAMETER",
                "x.Bag.add(String):0:s", List.of("@NotModified"), List.of(), Map.of(),
                List.of(new AnalysisModel.Why("NotModified", List.of(new AnalysisModel.WhyStep(URI, 8, 9, "read only")))));

        List<GutterText.Line> lines = GutterText.lines(add, List.of(add, s));

        assertEquals(List.of(
                new GutterText.Line(0, GutterText.Kind.ANNOTATIONS, "@Modified"),
                new GutterText.Line(1, GutterText.Kind.HEADING, "@Modified:"),
                new GutterText.Line(2, GutterText.Kind.STEP, "line 9: calls store(..)"),
                new GutterText.Line(2, GutterText.Kind.STEP, "Store.java:12: calls items.add(..)"),
                new GutterText.Line(2, GutterText.Kind.STEP, "a contract"),
                new GutterText.Line(1, GutterText.Kind.PARAMETER, "s: @NotModified"),
                new GutterText.Line(2, GutterText.Kind.HEADING, "@NotModified:"),
                new GutterText.Line(3, GutterText.Kind.STEP, "line 8: read only")), lines);
        assertEquals("""
                maddi: @Modified
                  @Modified:
                    ⇐ line 9: calls store(..)
                    ⇐ Store.java:12: calls items.add(..)
                    ⇐ a contract
                  s: @NotModified
                    @NotModified:
                      ⇐ line 8: read only""", GutterText.plain(lines));
    }

    @Test
    @DisplayName("why a type is not an annotation it does not show: a negative why")
    public void negative() {
        AnalysisModel.ElementAnnotation bag = new AnalysisModel.ElementAnnotation(URI, 3, 1, 9, 1, "TYPE", "x.Bag",
                List.of("@FinalFields"), List.of(), Map.of(),
                List.of(new AnalysisModel.Why("Container", List.of(new AnalysisModel.WhyStep(URI, 5, 9,
                        "parameter list of fill(..) is modified: calls list.add(..)")), true)));
        assertEquals("""
                maddi: @FinalFields
                  not @Container:
                    ⇐ line 5: parameter list of fill(..) is modified: calls list.add(..)""",
                GutterText.plain(GutterText.lines(bag, List.of(bag))));
    }

    @Test
    @DisplayName("a library note follows the annotations; a parameter's only when it differs from its method's")
    public void libraryNotes() {
        Map<String, String> annotated = Map.of(AnalysisModel.LIBRARY_NOTE, "from the annotated API");
        AnalysisModel.ElementAnnotation get = new AnalysisModel.ElementAnnotation(null, 1, 1, 1, 1, "METHOD",
                "java.util.List.get(int)", List.of("@NotModified"), List.of(), annotated);
        AnalysisModel.ElementAnnotation same = new AnalysisModel.ElementAnnotation(null, 1, 1, 1, 1, "PARAMETER",
                "java.util.List.get(int):0:index", List.of("@Independent"), List.of(), annotated);
        AnalysisModel.ElementAnnotation other = new AnalysisModel.ElementAnnotation(null, 1, 1, 1, 1, "PARAMETER",
                "java.util.List.get(int):1:x", List.of("@NotModified"), List.of(),
                Map.of(AnalysisModel.LIBRARY_NOTE, "a default"));

        assertEquals("""
                maddi: @NotModified
                  from the annotated API
                  index: @Independent
                  x: @NotModified
                    a default""", GutterText.plain(GutterText.lines(get, List.of(get, same, other))));
    }

    @Test
    @DisplayName("nothing to show: no lines; a method with annotated parameters only: an empty first line")
    public void empty() {
        AnalysisModel.ElementAnnotation m = new AnalysisModel.ElementAnnotation(URI, 1, 1, 1, 1, "METHOD",
                "x.Bag.m(int)", List.of(), List.of(), Map.of());
        assertTrue(GutterText.lines(m, List.of(m)).isEmpty());
        AnalysisModel.ElementAnnotation p = new AnalysisModel.ElementAnnotation(URI, 1, 1, 1, 1, "PARAMETER",
                "x.Bag.m(int):0:i", List.of("@NotModified"), List.of(), Map.of());
        assertEquals("maddi: \n  i: @NotModified", GutterText.plain(GutterText.lines(m, List.of(m, p))));
    }
}
