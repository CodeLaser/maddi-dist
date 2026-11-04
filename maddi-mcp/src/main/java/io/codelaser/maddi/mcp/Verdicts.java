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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.codelaser.maddi.ide.client.AnalysisModel;

/**
 * Questions about one analysis result, answered in the MCP process: the daemon sends every element's annotations
 * and every finding in its terminal frame, so nothing here goes back to it.
 * <p>
 * An element is named by its fully qualified name as the analyzer writes it: {@code a.b.C} for a type,
 * {@code a.b.C.field}, {@code a.b.C.method(String)}, {@code a.b.C.method(String):0:s} for a
 * parameter. A type's name selects its members too, and a name ending in a dot is a prefix (a package).
 */
public final class Verdicts {
    private Verdicts() {
    }

    /** The certainty fields every answer carries, so a value that may still strengthen is never read as final. */
    public static Map<String, Object> certaintyOf(AnalysisModel.Certainty certainty, boolean stopped) {
        return certaintyOf(certainty, stopped, false);
    }

    /** @param incremental the result is an incremental re-analysis after an edit (outcome INCREMENTAL) */
    public static Map<String, Object> certaintyOf(AnalysisModel.Certainty certainty, boolean stopped,
                                                  boolean incremental) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("certainty", certainty.name());
        String note = switch (certainty) {
            case PROVISIONAL -> stopped
                    ? "the run was stopped: these values hold, but a complete run may strengthen them"
                    : "the analysis is still running: these values hold, but may still strengthen";
            case FINAL -> "the fixpoint was certified: these values are final";
            case BEST_AVAILABLE -> incremental
                    ? "best available: re-analysed incrementally after an edit, which is never certified; values may"
                      + " be weaker than the code allows. analyze with cold for a certified run"
                    : "best available: the analysis did not reach a certified fixpoint, or some files"
                      + " did not parse; values may be weaker than the code allows";
            case UNKNOWN -> "no completed analysis";
        };
        out.put("certaintyNote", note);
        return out;
    }

    public static Map<String, Object> summary(AnalysisModel.Result result) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> kinds = new TreeMap<>();
        for (AnalysisModel.ElementAnnotation e : nonNull(result.elementAnnotations())) kinds.merge(e.kind(), 1, Integer::sum);
        out.put("elements", kinds);
        Map<String, Integer> categories = new TreeMap<>();
        for (AnalysisModel.Finding f : nonNull(result.findings())) {
            categories.merge(f.severity() + " " + f.category(), 1, Integer::sum);
        }
        out.put("findings", categories);
        if (result.outcome() != null) out.put("outcome", result.outcome());
        if (result.parseErrorCount() > 0) out.put("parseErrors", result.parseErrorCount());
        if (!nonNull(result.initializationProblems()).isEmpty()) {
            out.put("initializationProblems", result.initializationProblems());
        }
        if (result.elapsedMillis() > 0) out.put("elapsedMillis", result.elapsedMillis());
        return out;
    }

    /**
     * The elements selected by name, kind, file and annotation.
     *
     * @param element    a fully qualified name; see the class comment. Null: every element
     * @param kind       TYPE, METHOD, FIELD or PARAMETER; null for all
     * @param file       a suffix of the source file's path, e.g. {@code a/b/C.java}; null for all
     * @param annotation a text the element's annotations must contain, e.g. {@code @Immutable}; null for all
     */
    public static List<AnalysisModel.ElementAnnotation> select(AnalysisModel.Result result, String element,
                                                               String kind, String file, String annotation) {
        List<AnalysisModel.ElementAnnotation> out = new ArrayList<>();
        for (AnalysisModel.ElementAnnotation e : nonNull(result.elementAnnotations())) {
            if (element != null && !matches(e.fqn(), element)) continue;
            if (kind != null && !kind.equalsIgnoreCase(e.kind())) continue;
            if (file != null && (e.uri() == null || !e.uri().endsWith(file))) continue;
            if (annotation != null && nonNull(e.displayAnnotations()).stream().noneMatch(a -> a.contains(annotation))) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    static boolean matches(String fqn, String query) {
        if (fqn == null) return false;
        if (query.endsWith(".")) return fqn.startsWith(query);
        return fqn.equals(query) || fqn.startsWith(query + ".") || fqn.startsWith(query + "(")
               || fqn.startsWith(query + ":");
    }

    /** An element, short: what an overview of many needs. */
    public static Map<String, Object> brief(AnalysisModel.ElementAnnotation e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", e.kind());
        out.put("fqn", e.fqn());
        out.put("annotations", nonNull(e.displayAnnotations()));
        if (AnalysisModel.isEventual(e)) out.put("eventual", true);
        if (e.uri() != null) out.put("location", location(e.uri(), e.beginLine()));
        return out;
    }

    /**
     * One element in full: its annotations with polarity, the analyzer's properties (where a "why not" is read:
     * the values behind the annotations), the culprits it records, and the findings about it (see {@link #concerns}).
     */
    public static Map<String, Object> explain(AnalysisModel.Result result, AnalysisModel.ElementAnnotation e) {
        Map<String, Object> out = brief(e);
        List<Map<String, Object>> annotations = new ArrayList<>();
        for (AnalysisModel.Annotation a : nonNull(e.annotations())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", a.text());
            m.put("polarity", a.polarity());
            if (a.contextDefault()) m.put("contextDefault", true);
            annotations.add(m);
        }
        out.put("annotationDetail", annotations);
        if (e.properties() != null && !e.properties().isEmpty()) out.put("properties", new TreeMap<>(e.properties()));
        List<Map<String, Object>> whys = new ArrayList<>();
        for (AnalysisModel.Why why : e.whys()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("annotation", why.annotation());
            // the culprit of a verdict the element does NOT have: why it is not @Container
            if (why.negates()) m.put("holds", false);
            m.put("steps", nonNull(why.steps()).stream()
                    .map(s -> location(s.uri(), s.line()) + ":" + s.column() + " " + s.text()).toList());
            whys.add(m);
        }
        if (!whys.isEmpty()) out.put("whys", whys);
        List<Map<String, Object>> findings = new ArrayList<>();
        for (AnalysisModel.Finding f : nonNull(result.findings())) {
            String via = concerns(f, e);
            if (via == null) continue;
            Map<String, Object> m = finding(f);
            if (!"inside".equals(via)) m.put("via", via);
            findings.add(m);
        }
        if (!findings.isEmpty()) out.put("findings", findings);
        return out;
    }

    /**
     * Whether a finding is about this element, and how: located inside it ({@code inside}), through a cause located
     * inside it ({@code cause}), or naming it in its message ({@code named}); null when none of these.
     * <p>
     * ⚠ Location alone is not enough. A violation of a contract on an interface is reported where the implementation
     * breaks it: an assignment in {@code Circle.area()} makes the abstract {@code Shape.area()} modifying, and the
     * finding sits in Circle.java while being the whole reason {@code Shape} is not immutable.
     */
    static String concerns(AnalysisModel.Finding f, AnalysisModel.ElementAnnotation e) {
        if (inside(f, e)) return "inside";
        if (causeInside(f.causes(), e)) return "cause";
        if (names(f, e.fqn())) return "named";
        return null;
    }

    private static boolean causeInside(List<AnalysisModel.Finding> causes, AnalysisModel.ElementAnnotation e) {
        for (AnalysisModel.Finding cause : nonNull(causes)) {
            if (inside(cause, e) || causeInside(cause.causes(), e)) return true;
        }
        return false;
    }

    /* the fqn as a whole name: not the start of a longer identifier (Shape in ShapeFactory) */
    private static boolean names(AnalysisModel.Finding f, String fqn) {
        if (fqn == null || f.message() == null) return false;
        String message = f.message();
        for (int at = message.indexOf(fqn); at >= 0; at = message.indexOf(fqn, at + 1)) {
            int end = at + fqn.length();
            boolean startOk = at == 0 || !Character.isJavaIdentifierPart(message.charAt(at - 1))
                                         && message.charAt(at - 1) != '.';
            if (startOk && (end == message.length() || !Character.isJavaIdentifierPart(message.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The elements at a position, outermost first. Without a column a parameter is never chosen: on a method's first
     * line it is the method that is meant, not its first parameter.
     */
    public static List<AnalysisModel.ElementAnnotation> at(AnalysisModel.Result result, String file, int line,
                                                           Integer column) {
        List<AnalysisModel.ElementAnnotation> out = new ArrayList<>();
        for (AnalysisModel.ElementAnnotation e : nonNull(result.elementAnnotations())) {
            if (e.uri() == null || !e.uri().endsWith(file) || e.beginLine() == null || e.endLine() == null) continue;
            if (line < e.beginLine() || line > e.endLine()) continue;
            if (column == null) {
                if ("PARAMETER".equals(e.kind())) continue;
            } else if (!containsColumn(e, line, column)) {
                continue;
            }
            out.add(e);
        }
        // outermost first: a larger range first, then the earlier start
        out.sort(Comparator
                .comparingInt((AnalysisModel.ElementAnnotation e) -> -(e.endLine() - e.beginLine()))
                .thenComparingInt(e -> e.beginLine())
                .thenComparingInt(e -> e.beginCol() == null ? 0 : e.beginCol())
                .thenComparingInt(e -> -(e.endCol() == null ? 0 : e.endCol())));
        return out;
    }

    private static boolean containsColumn(AnalysisModel.ElementAnnotation e, int line, int column) {
        if (line == e.beginLine() && e.beginCol() != null && column < e.beginCol()) return false;
        return line != e.endLine() || e.endCol() == null || column <= e.endCol();
    }

    private static boolean inside(AnalysisModel.Finding f, AnalysisModel.ElementAnnotation e) {
        if (f.uri() == null || !f.uri().equals(e.uri()) || f.beginLine() == null) return false;
        if (e.beginLine() == null || e.endLine() == null) return false;
        return f.beginLine() >= e.beginLine() && f.beginLine() <= e.endLine();
    }

    /**
     * The findings selected by category (a prefix: {@code near-miss-} selects all near misses), severity and file
     * or element.
     */
    public static List<AnalysisModel.Finding> findings(AnalysisModel.Result result, String category,
                                                       String severity, String file, String element) {
        List<AnalysisModel.ElementAnnotation> scope = element == null ? null
                : select(result, element, null, null, null);
        List<AnalysisModel.Finding> out = new ArrayList<>();
        for (AnalysisModel.Finding f : nonNull(result.findings())) {
            if (category != null && (f.category() == null || !f.category().startsWith(category))) continue;
            if (severity != null && !severity.equalsIgnoreCase(f.severity())) continue;
            if (file != null && (f.uri() == null || !f.uri().endsWith(file))) continue;
            if (scope != null && scope.stream().noneMatch(e -> concerns(f, e) != null)) continue;
            out.add(f);
        }
        return out;
    }

    /** A finding with its why-chain. */
    public static Map<String, Object> finding(AnalysisModel.Finding f) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("severity", f.severity());
        out.put("category", f.category());
        out.put("message", f.message());
        if (AnalysisModel.isNearMiss(f)) out.put("advisory", true);
        if (f.uri() != null) out.put("location", location(f.uri(), f.beginLine()));
        if (f.causes() != null && !f.causes().isEmpty()) {
            out.put("causes", f.causes().stream().map(Verdicts::finding).toList());
        }
        return out;
    }

    private static String location(String uri, Integer line) {
        String path = uri.startsWith("file:") ? uri.substring(uri.indexOf(':') + 1).replaceFirst("^//", "") : uri;
        return line == null ? path : path + ":" + line;
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }
}
