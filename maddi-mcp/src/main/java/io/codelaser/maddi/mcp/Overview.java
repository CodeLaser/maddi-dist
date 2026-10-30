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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The state of a project, a package or a type in one answer: types by immutability level, methods by modification,
 * the modified fields and parameters, the findings by category, and per package when there is more than one. What
 * paging through {@code verdicts} would otherwise assemble. Every list is cut at {@code limit}, with its count.
 */
public final class Overview {
    private Overview() {
    }

    public static Map<String, Object> of(AnalysisModel.Result result, String element, int limit) {
        List<AnalysisModel.ElementAnnotation> selected = Verdicts.select(result, element, null, null, null);
        Map<String, Object> out = new LinkedHashMap<>();

        List<AnalysisModel.ElementAnnotation> types = ofKind(selected, "TYPE");
        Map<String, List<String>> byLevel = new TreeMap<>(Comparator.comparingInt(Overview::rank)
                .thenComparing(Comparator.naturalOrder()));
        for (AnalysisModel.ElementAnnotation type : types) {
            byLevel.computeIfAbsent(level(type), l -> new ArrayList<>()).add(type.fqn());
        }
        Map<String, Object> levels = new LinkedHashMap<>();
        byLevel.forEach((level, fqns) -> levels.put(level, group(fqns, limit)));
        Map<String, Object> typesOut = new LinkedHashMap<>();
        typesOut.put("total", types.size());
        typesOut.put("byLevel", levels);
        out.put("types", typesOut);

        List<String> modifying = new ArrayList<>();
        List<String> hiddenContentOnly = new ArrayList<>();
        int notModified = 0;
        int methods = 0;
        for (AnalysisModel.ElementAnnotation m : ofKind(selected, "METHOD")) {
            if (m.fqn().contains(".<init>(")) continue; // a constructor modifies the object it builds, by definition
            methods++;
            List<String> annotations = annotations(m);
            if (annotations.stream().anyMatch(a -> a.startsWith("@Modified(hc=true"))) hiddenContentOnly.add(m.fqn());
            else if (annotations.stream().anyMatch(a -> a.startsWith("@Modified"))) modifying.add(m.fqn());
            else if (annotations.contains("@NotModified")) notModified++;
        }
        Map<String, Object> methodsOut = new LinkedHashMap<>();
        methodsOut.put("total", methods);
        methodsOut.put("modifying", group(modifying, limit));
        methodsOut.put("modifyingHiddenContentOnly", group(hiddenContentOnly, limit));
        methodsOut.put("notModified", notModified);
        out.put("methods", methodsOut);

        List<AnalysisModel.ElementAnnotation> fields = ofKind(selected, "FIELD");
        Map<String, Object> fieldsOut = new LinkedHashMap<>();
        fieldsOut.put("total", fields.size());
        fieldsOut.put("modified", group(fields.stream().filter(Overview::modified)
                .map(AnalysisModel.ElementAnnotation::fqn).toList(), limit));
        out.put("fields", fieldsOut);
        out.put("modifiedParameters", group(ofKind(selected, "PARAMETER").stream().filter(Overview::modified)
                .map(AnalysisModel.ElementAnnotation::fqn).toList(), limit));

        Map<String, Integer> findings = new TreeMap<>();
        for (AnalysisModel.Finding f : Verdicts.findings(result, null, null, null, element)) {
            findings.merge(f.severity() + " " + f.category(), 1, Integer::sum);
        }
        out.put("findings", findings);

        Map<String, Map<String, Integer>> packages = packages(types);
        if (packages.size() > 1) out.put("packages", packages);
        return out;
    }

    private static final java.util.regex.Pattern AFTER = java.util.regex.Pattern.compile("after=\"[^\"]*\"");

    /*
     The type's level on the immutability ladder, as displayed, with an eventual verdict's roster of marks left out
     (after=…): a group per distinct roster would split one level into as many as there are types. explain gives
     the roster.
     */
    static String level(AnalysisModel.ElementAnnotation type) {
        for (String a : annotations(type)) {
            if (a.startsWith("@Immutable") || a.startsWith("@FinalFields") || a.startsWith("@Mutable")) {
                return AFTER.matcher(a).replaceAll("after=…");
            }
        }
        String property = type.properties() == null ? null : type.properties().get("immutableType");
        return property != null ? property : "(no verdict)";
    }

    /*
     Strongest first: immutable, then immutable but for the hidden content, then immutable only after a mark; the
     container property orders within a rung, it is not a rung of its own.
     */
    private static int rank(String level) {
        if (level.startsWith("@Immutable")) {
            int rung = level.contains("after=") ? 4 : level.contains("hc=true") ? 2 : 0;
            return rung + (level.startsWith("@ImmutableContainer") ? 0 : 1);
        }
        if (level.startsWith("@FinalFields")) return 6;
        if (level.startsWith("@Mutable")) return 7;
        return 8;
    }

    /*
     Per package: its types, how many reach an immutable level, how many are mutable. A nested type counts in the
     package of the type it is nested in, which a name alone cannot tell from a package.
     */
    private static Map<String, Map<String, Integer>> packages(List<AnalysisModel.ElementAnnotation> types) {
        Set<String> names = new HashSet<>();
        for (AnalysisModel.ElementAnnotation t : types) names.add(t.fqn());
        Map<String, Map<String, Integer>> out = new TreeMap<>();
        for (AnalysisModel.ElementAnnotation t : types) {
            String topLevel = t.fqn();
            for (int dot = topLevel.lastIndexOf('.'); dot > 0; dot = topLevel.lastIndexOf('.', dot - 1)) {
                if (names.contains(t.fqn().substring(0, dot))) topLevel = t.fqn().substring(0, dot);
            }
            int dot = topLevel.lastIndexOf('.');
            String pkg = dot < 0 ? "(default package)" : topLevel.substring(0, dot);
            Map<String, Integer> counts = out.computeIfAbsent(pkg, p -> new LinkedHashMap<>(Map.of()));
            counts.merge("types", 1, Integer::sum);
            String level = level(t);
            if (level.startsWith("@Immutable")) counts.merge("immutable", 1, Integer::sum);
            if (level.startsWith("@Mutable")) counts.merge("mutable", 1, Integer::sum);
        }
        return out;
    }

    private static boolean modified(AnalysisModel.ElementAnnotation e) {
        return annotations(e).stream().anyMatch(a -> a.startsWith("@Modified"));
    }

    private static List<AnalysisModel.ElementAnnotation> ofKind(List<AnalysisModel.ElementAnnotation> elements,
                                                                String kind) {
        return elements.stream().filter(e -> kind.equals(e.kind()) && e.fqn() != null).toList();
    }

    private static List<String> annotations(AnalysisModel.ElementAnnotation e) {
        return e.displayAnnotations() == null ? List.of() : e.displayAnnotations();
    }

    private static Map<String, Object> group(List<String> unsorted, int limit) {
        List<String> fqns = unsorted.stream().sorted().toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", fqns.size());
        if (!fqns.isEmpty()) out.put("elements", fqns.subList(0, Math.min(limit, fqns.size())));
        if (fqns.size() > limit) out.put("more", fqns.size() - limit + " not listed: narrow 'element', or raise 'limit'");
        return out;
    }
}
