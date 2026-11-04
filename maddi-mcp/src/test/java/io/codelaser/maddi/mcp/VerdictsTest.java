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

package io.codelaser.maddi.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.codelaser.maddi.ide.client.AnalysisModel;

public class VerdictsTest {

    private static AnalysisModel.ElementAnnotation element(String kind, String fqn, int from, int to,
                                                           String... annotations) {
        return new AnalysisModel.ElementAnnotation("file:///p/src/a/C.java", from, 1, to, 1, kind, fqn,
                List.of(annotations), List.of(), Map.of());
    }

    private static final AnalysisModel.Result RESULT = new AnalysisModel.Result("r", List.of(
            new AnalysisModel.Finding("file:///p/src/a/C.java", 5, 3, 5, 9, "ERROR", "contract-violation",
                    "modifies", List.of()),
            new AnalysisModel.Finding("file:///p/src/a/D.java", 2, 1, 2, 1, "WARN", "near-miss-container",
                    "one away", List.of())),
            List.of(element("TYPE", "a.C", 1, 10, "@FinalFields"),
                    element("METHOD", "a.C.m(String)", 4, 6, "@Modified"),
                    element("PARAMETER", "a.C.m(String):0:s", 4, 4, "@NotModified"),
                    element("TYPE", "a.Cx", 12, 14, "@Immutable"),
                    element("TYPE", "b.E", 1, 3, "@Immutable")),
            List.of(), 0, 0, 10, "CERTIFIED");

    private static List<String> fqns(List<AnalysisModel.ElementAnnotation> elements) {
        return elements.stream().map(AnalysisModel.ElementAnnotation::fqn).toList();
    }

    @Test
    public void aTypeSelectsItsMembersButNotItsNamesakes() {
        assertEquals(List.of("a.C", "a.C.m(String)", "a.C.m(String):0:s"),
                fqns(Verdicts.select(RESULT, "a.C", null, null, null)));
        assertEquals(List.of("a.C.m(String)", "a.C.m(String):0:s"),
                fqns(Verdicts.select(RESULT, "a.C.m", null, null, null)));
        assertEquals(4, Verdicts.select(RESULT, "a.", null, null, null).size(), "a package");
        assertEquals(List.of("a.Cx", "b.E"), fqns(Verdicts.select(RESULT, null, "type", null, "@Immutable")));
    }

    @Test
    public void findingsBelongToTheElementsAroundThem() {
        assertEquals(1, Verdicts.findings(RESULT, null, null, null, "a.C.m").size());
        assertEquals(0, Verdicts.findings(RESULT, null, null, null, "a.Cx").size());
        assertEquals(1, Verdicts.findings(RESULT, "near-miss-", null, null, null).size());
        assertEquals(1, Verdicts.findings(RESULT, null, "error", null, null).size());
        Map<String, Object> explained = Verdicts.explain(RESULT, RESULT.elementAnnotations().get(1));
        assertNotNull(explained.get("findings"));
        assertEquals("/p/src/a/C.java:4", explained.get("location"));
        assertEquals(Boolean.TRUE, Verdicts.finding(RESULT.findings().get(1)).get("advisory"));
    }

    @Test
    public void certainty() {
        assertEquals("FINAL", Verdicts.certaintyOf(AnalysisModel.certaintyOf(RESULT), false).get("certainty"));
        AnalysisRun run = new AnalysisRun("run-1", "p");
        assertEquals(AnalysisModel.Certainty.UNKNOWN, run.certainty(), "nothing streamed yet");
        run.stop();
        assertEquals("STOPPED", run.describe().get("state"));
    }

    /* Shape's contract broken in Circle.java: the finding is located there, one cause in Shape */
    private static final AnalysisModel.Result CONTRACT = new AnalysisModel.Result("r", List.of(
            new AnalysisModel.Finding("file:///p/Circle.java", 8, 9, 8, 20, "ERROR", "contract-violation",
                    "Circle.area() assigns a static field, which makes s.Shape.area() modifying", List.of(
                    new AnalysisModel.Finding("file:///p/Shape.java", 11, 5, 11, 20, "WARN", "general",
                            "abstract method is modifying", List.of()))),
            new AnalysisModel.Finding("file:///p/Other.java", 3, 1, 3, 1, "WARN", "general",
                    "s.ShapeFactory makes things; s.Shape2 too", List.of())),
            List.of(new AnalysisModel.ElementAnnotation("file:///p/Shape.java", 9, 1, 12, 1, "TYPE", "s.Shape",
                            List.of("@FinalFields"), List.of(), Map.of()),
                    new AnalysisModel.ElementAnnotation("file:///p/Circle.java", 4, 1, 11, 1, "TYPE", "s.Circle",
                            List.of("@Mutable"), List.of(), Map.of()),
                    new AnalysisModel.ElementAnnotation("file:///p/Circle.java", 6, 5, 10, 5, "METHOD",
                            "s.Circle.area()", List.of("@Modified"), List.of(), Map.of()),
                    new AnalysisModel.ElementAnnotation("file:///p/Circle.java", 6, 30, 6, 40, "PARAMETER",
                            "s.Circle.area():0:p", List.of(), List.of(), Map.of())),
            List.of(), 0, 0, 10, "CERTIFIED");

    @Test
    public void aFindingElsewhereConcernsTheTypeThroughItsCause() {
        AnalysisModel.ElementAnnotation shape = CONTRACT.elementAnnotations().getFirst();
        AnalysisModel.Finding violation = CONTRACT.findings().getFirst();
        assertEquals("cause", Verdicts.concerns(violation, shape));
        assertNull(Verdicts.concerns(CONTRACT.findings().get(1), shape), "ShapeFactory and Shape2 are not Shape");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) Verdicts.explain(CONTRACT, shape)
                .get("findings");
        assertEquals(1, findings.size());
        assertEquals("cause", findings.getFirst().get("via"));
        assertEquals(1, Verdicts.findings(CONTRACT, null, null, null, "s.Shape").size());

        AnalysisModel.Finding named = new AnalysisModel.Finding(null, null, null, null, null, "WARN", "general",
                "see s.Shape.", List.of());
        assertEquals("named", Verdicts.concerns(named, shape));
    }

    @Test
    public void elementsAtAPosition() {
        assertEquals(List.of("s.Circle", "s.Circle.area()"), fqns(Verdicts.at(CONTRACT, "Circle.java", 6, null)),
                "a method's line: the method, not its parameter");
        assertEquals(List.of("s.Circle", "s.Circle.area()", "s.Circle.area():0:p"),
                fqns(Verdicts.at(CONTRACT, "Circle.java", 6, 32)));
        assertEquals(List.of("s.Circle"), fqns(Verdicts.at(CONTRACT, "p/Circle.java", 4, null)));
        assertEquals(List.of(), Verdicts.at(CONTRACT, "Circle.java", 30, null));
    }
}
