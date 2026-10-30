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

import io.codelaser.maddi.ide.client.AnalysisModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
}
