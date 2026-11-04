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

public class RunDiffTest {

    private static AnalysisModel.ElementAnnotation element(String kind, String fqn, int line, String... annotations) {
        return new AnalysisModel.ElementAnnotation("file:///p/C.java", line, 1, line + 2, 1, kind, fqn,
                List.of(annotations), List.of(), Map.of());
    }

    private static AnalysisModel.Result result(List<AnalysisModel.Finding> findings,
                                               AnalysisModel.ElementAnnotation... elements) {
        return new AnalysisModel.Result("r", findings, List.of(elements), List.of(), 0, 0, 1, "CERTIFIED");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void anEditSeenByName() {
        AnalysisModel.Finding violation = new AnalysisModel.Finding("file:///p/C.java", 5, 1, 5, 1, "ERROR",
                "contract-violation", "a.C.m() modifies", List.of());
        AnalysisModel.Result before = result(List.of(),
                element("TYPE", "a.C", 1, "@ImmutableContainer"),
                element("METHOD", "a.C.m()", 4, "@NotModified"),
                element("METHOD", "a.C.gone()", 8, "@NotModified"));
        // the edit moved m() down two lines: matched by name, it is a change of verdict, not a new element
        AnalysisModel.Result after = result(List.of(violation),
                element("TYPE", "a.C", 1, "@Mutable"),
                element("METHOD", "a.C.m()", 6, "@Modified"),
                element("METHOD", "a.C.added()", 10, "@NotModified"));

        Map<String, Object> diff = RunDiff.diff(before, after, null);
        List<Map<String, Object>> changed = (List<Map<String, Object>>) diff.get("changed");
        assertEquals(List.of("a.C", "a.C.m()"), changed.stream().map(m -> m.get("fqn")).toList());
        assertEquals(List.of("@Modified"), changed.get(1).get("added"));
        assertEquals(List.of("@NotModified"), changed.get(1).get("removed"));
        assertEquals("a.C.added()", ((List<Map<String, Object>>) diff.get("appeared")).getFirst().get("fqn"));
        assertEquals("a.C.gone()", ((List<Map<String, Object>>) diff.get("disappeared")).getFirst().get("fqn"));
        assertEquals(1, ((List<?>) diff.get("newFindings")).size());
        assertNull(diff.get("goneFindings"));

        Map<String, Object> onlyM = RunDiff.diff(before, after, "a.C.m");
        assertEquals(1, ((List<?>) onlyM.get("changed")).size());
        assertNull(onlyM.get("appeared"));

        assertEquals(List.of(), RunDiff.diff(before, before, null).get("changed"));
    }
}
