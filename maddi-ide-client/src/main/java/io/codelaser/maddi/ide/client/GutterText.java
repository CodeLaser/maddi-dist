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

import java.util.ArrayList;
import java.util.List;

/**
 * What a type's, method's or field's gutter mark says, as lines, so every IDE says the same: the annotations, where a
 * library element's values come from, each annotation's why (the chain of statements, the element's own first), and
 * for a method its parameters with theirs. An IDE only renders the lines: indents {@link Line#depth()}, puts
 * {@link #ANNOTATIONS_PREFIX} and {@link #STEP_PREFIX} before their kinds, and escapes as its tooltip needs.
 */
public final class GutterText {

    public static final String ANNOTATIONS_PREFIX = "maddi: ";
    public static final String STEP_PREFIX = "⇐ ";

    public enum Kind {
        /** The element's annotations, space-separated; the first line, at depth 0. */
        ANNOTATIONS,
        /** Where a library element's values come from: our annotated API, or the default. */
        NOTE,
        /** A why's heading: the annotation it explains, e.g. {@code @Modified:}, or does not hold: {@code not @Container:}. */
        HEADING,
        /** One statement of a why, with its place when it has one: {@code line 9: calls store(..)}. */
        STEP,
        /** A method's parameter and its annotations: {@code s: @NotModified}. */
        PARAMETER
    }

    public record Line(int depth, Kind kind, String text) {
    }

    private GutterText() {
    }

    /**
     * The lines for {@code element}; empty when it has nothing to show. {@code all} is the result's (or the file's)
     * elements, where a method's parameters are found.
     */
    public static List<Line> lines(AnalysisModel.ElementAnnotation element, List<AnalysisModel.ElementAnnotation> all) {
        List<AnalysisModel.ElementAnnotation> parameters = AnalysisModel.parametersOf(all, element);
        List<String> own = element.displayAnnotations() == null ? List.of() : element.displayAnnotations();
        if (own.isEmpty() && parameters.isEmpty()) return List.of();
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(0, Kind.ANNOTATIONS, String.join(" ", own)));
        String note = noteOf(element);
        if (note != null) lines.add(new Line(1, Kind.NOTE, note));
        addWhys(lines, element, 1);
        for (AnalysisModel.ElementAnnotation p : parameters) {
            lines.add(new Line(1, Kind.PARAMETER, p.fqn().substring(p.fqn().lastIndexOf(':') + 1) + ": "
                                                 + String.join(" ", p.displayAnnotations())));
            String parameterNote = noteOf(p);
            if (parameterNote != null && !parameterNote.equals(note)) lines.add(new Line(2, Kind.NOTE, parameterNote));
            addWhys(lines, p, 2);
        }
        return List.copyOf(lines);
    }

    /** The lines as plain text: two spaces per depth, one line each. */
    public static String plain(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line line : lines) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("  ".repeat(line.depth())).append(prefixOf(line.kind())).append(line.text());
        }
        return sb.toString();
    }

    /** What an IDE puts before a line of this kind. */
    public static String prefixOf(Kind kind) {
        return switch (kind) {
            case ANNOTATIONS -> ANNOTATIONS_PREFIX;
            case STEP -> STEP_PREFIX;
            default -> "";
        };
    }

    private static void addWhys(List<Line> lines, AnalysisModel.ElementAnnotation element, int depth) {
        if (element.whys() == null) return;
        for (AnalysisModel.Why why : element.whys()) {
            if (why.steps() == null || why.steps().isEmpty()) continue;
            lines.add(new Line(depth, Kind.HEADING, (why.negates() ? "not @" : "@") + why.annotation() + ':'));
            for (AnalysisModel.WhyStep step : why.steps()) {
                // a step without a place (a contract, an annotation) is its text alone
                String place = AnalysisModel.whereOf(element.uri(), step);
                lines.add(new Line(depth + 1, Kind.STEP, (place.isEmpty() ? "" : place + ": ") + step.text()));
            }
        }
    }

    private static String noteOf(AnalysisModel.ElementAnnotation element) {
        return element.properties() == null ? null : element.properties().get(AnalysisModel.LIBRARY_NOTE);
    }
}
