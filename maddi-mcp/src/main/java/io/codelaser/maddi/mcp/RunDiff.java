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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What changed between two results: the question after an edit. Elements are matched by kind and fully qualified
 * name, as {@link AnalysisModel#merge} matches them, never by position, which an edit moves. Findings are matched by
 * severity, category and message, for the same reason.
 */
public final class RunDiff {
    private RunDiff() {
    }

    /**
     * @param element limits the comparison to these elements (see {@link Verdicts#select}); null for all
     */
    public static Map<String, Object> diff(AnalysisModel.Result from, AnalysisModel.Result to, String element) {
        Map<String, AnalysisModel.ElementAnnotation> before = byIdentity(from, element);
        Map<String, AnalysisModel.ElementAnnotation> after = byIdentity(to, element);

        List<Map<String, Object>> changed = new ArrayList<>();
        List<Map<String, Object>> appeared = new ArrayList<>();
        for (var entry : after.entrySet()) {
            AnalysisModel.ElementAnnotation was = before.get(entry.getKey());
            AnalysisModel.ElementAnnotation is = entry.getValue();
            if (was == null) {
                appeared.add(Verdicts.brief(is));
                continue;
            }
            Set<String> old = new LinkedHashSet<>(texts(was));
            Set<String> now = new LinkedHashSet<>(texts(is));
            if (old.equals(now)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", is.kind());
            m.put("fqn", is.fqn());
            m.put("before", texts(was));
            m.put("after", texts(is));
            List<String> added = now.stream().filter(a -> !old.contains(a)).toList();
            List<String> removed = old.stream().filter(a -> !now.contains(a)).toList();
            if (!added.isEmpty()) m.put("added", added);
            if (!removed.isEmpty()) m.put("removed", removed);
            changed.add(m);
        }
        List<Map<String, Object>> disappeared = before.entrySet().stream()
                .filter(e -> !after.containsKey(e.getKey())).map(e -> Verdicts.brief(e.getValue())).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changed", changed);
        if (!appeared.isEmpty()) out.put("appeared", appeared);
        if (!disappeared.isEmpty()) out.put("disappeared", disappeared);
        Map<String, AnalysisModel.Finding> findingsBefore = findings(from, element);
        Map<String, AnalysisModel.Finding> findingsAfter = findings(to, element);
        List<Map<String, Object>> newFindings = findingsAfter.entrySet().stream()
                .filter(e -> !findingsBefore.containsKey(e.getKey())).map(e -> Verdicts.finding(e.getValue())).toList();
        List<Map<String, Object>> goneFindings = findingsBefore.entrySet().stream()
                .filter(e -> !findingsAfter.containsKey(e.getKey())).map(e -> Verdicts.finding(e.getValue())).toList();
        if (!newFindings.isEmpty()) out.put("newFindings", newFindings);
        if (!goneFindings.isEmpty()) out.put("goneFindings", goneFindings);
        return out;
    }

    private static Map<String, AnalysisModel.ElementAnnotation> byIdentity(AnalysisModel.Result result,
                                                                            String element) {
        Map<String, AnalysisModel.ElementAnnotation> out = new LinkedHashMap<>();
        for (AnalysisModel.ElementAnnotation e : Verdicts.select(result, element, null, null, null)) {
            out.put(e.kind() + " " + e.fqn(), e);
        }
        return out;
    }

    private static List<String> texts(AnalysisModel.ElementAnnotation e) {
        return e.displayAnnotations() == null ? List.of() : e.displayAnnotations();
    }

    private static Map<String, AnalysisModel.Finding> findings(AnalysisModel.Result result, String element) {
        Map<String, AnalysisModel.Finding> out = new LinkedHashMap<>();
        for (AnalysisModel.Finding f : Verdicts.findings(result, null, null, null, element)) {
            out.putIfAbsent(String.join("|", Objects.toString(f.severity()), Objects.toString(f.category()),
                    Objects.toString(f.message())), f);
        }
        return out;
    }
}
