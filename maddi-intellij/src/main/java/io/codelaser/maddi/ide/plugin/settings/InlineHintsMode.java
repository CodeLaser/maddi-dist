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

package io.codelaser.maddi.ide.plugin.settings;

import io.codelaser.maddi.ide.client.AnalysisModel;

/**
 * Which computed annotations appear as inline hints (the gutter always shows the full set). Applied per
 * annotation using its polarity and context-default flag.
 */
public enum InlineHintsMode {
    HIDE_CONTEXT_DEFAULTS("Hide context defaults"),
    ALL("Show all"),
    POSITIVE_ONLY("Positive only (safe: @NotModified, @Immutable, …)"),
    NEGATIVE_ONLY("Negative only (@Modified, @Mutable, …)"),
    NONE("Off (gutter only)");

    private final String label;

    InlineHintsMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label; // rendered in the settings combo box
    }

    /** Whether an annotation should be shown inline under this mode. Neutral annotations are always kept. */
    public boolean shows(AnalysisModel.Annotation a) {
        return switch (this) {
            case ALL -> true;
            case NONE -> false;
            case HIDE_CONTEXT_DEFAULTS -> !a.contextDefault();
            case POSITIVE_ONLY -> !"NEGATIVE".equals(a.polarity());
            case NEGATIVE_ONLY -> !"POSITIVE".equals(a.polarity());
        };
    }
}
