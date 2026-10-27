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

package io.codelaser.maddi.ide.eclipse;

import io.codelaser.maddi.ide.client.AnalysisModel;

import java.util.List;

/**
 * Which computed annotations appear as gutter hints, mirroring the IntelliJ plugin's inline-hints filter.
 * maddi's decorator collapses "baseline" and "negative polarity" onto the same boundary, so the choices are
 * two axes: <b>context-default-ness</b> (is this annotation already implied by the enclosing declaration?)
 * and <b>polarity</b> (proven-positive vs. baseline-negative).
 */
public enum HintFilter {

    /** Default: hide annotations the enclosing declaration already implies; keep the surprises. */
    HIDE_CONTEXT_DEFAULTS("Hide implied defaults"),
    ALL("Everything"),
    POSITIVE_ONLY("Only positive (immutable, not-modified, …)"),
    NEGATIVE_ONLY("Only negative (mutable, modifying, …)"),
    NONE("Nothing");

    private final String label;

    HintFilter(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean shows(AnalysisModel.Annotation annotation) {
        return switch (this) {
            case ALL -> true;
            case NONE -> false;
            case HIDE_CONTEXT_DEFAULTS -> !annotation.contextDefault();
            case POSITIVE_ONLY -> !"NEGATIVE".equals(annotation.polarity());
            case NEGATIVE_ONLY -> !"POSITIVE".equals(annotation.polarity());
        };
    }

    /**
     * What this element should read as under this filter: the shown annotations joined, or empty for
     * "show nothing here". Shared by every surface (gutter markers, inline code minings) so one filter
     * setting means the same thing everywhere.
     */
    public String textFor(AnalysisModel.ElementAnnotation element) {
        List<AnalysisModel.Annotation> annotations = element.annotations();
        if (annotations == null || annotations.isEmpty()) {
            // no tagged annotations to filter on; fall back to the full display set unless suppressed entirely
            return this == NONE || element.displayAnnotations() == null
                    ? "" : String.join(" ", element.displayAnnotations());
        }
        StringBuilder sb = new StringBuilder();
        for (AnalysisModel.Annotation annotation : annotations) {
            if (!shows(annotation)) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(annotation.text());
        }
        return sb.toString();
    }
}
