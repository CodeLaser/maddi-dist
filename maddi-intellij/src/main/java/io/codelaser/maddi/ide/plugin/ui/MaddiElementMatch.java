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
package io.codelaser.maddi.ide.plugin.ui;

import java.util.List;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;

import io.codelaser.maddi.ide.client.AnalysisModel;

/**
 * Which analysed element a declaration's name belongs to: of the elements of the wanted kind whose range contains
 * the name, the SMALLEST. A nested type or member sits inside its enclosing type's range, so several same-kind
 * elements can contain one name, and only the innermost is its own.
 * <p>
 * The kinds are tried in order, the first that matches winning: a Kotlin property is its backing field, or, when
 * it has none, its accessor.
 */
public final class MaddiElementMatch {
    private MaddiElementMatch() {
    }

    public static @Nullable AnalysisModel.ElementAnnotation find(List<AnalysisModel.ElementAnnotation> annotations,
                                                                 Document doc, List<String> kinds, int offset,
                                                                 Predicate<AnalysisModel.ElementAnnotation> shown) {
        for (String kind : kinds) {
            AnalysisModel.ElementAnnotation match = null;
            int bestLength = Integer.MAX_VALUE;
            for (AnalysisModel.ElementAnnotation a : annotations) {
                if (!kind.equals(a.kind()) || !shown.test(a)) continue;
                TextRange r = MaddiPositions.range(doc, a.beginLine(), a.beginCol(), a.endLine(), a.endCol());
                if (r != null && r.contains(offset) && r.getLength() < bestLength) {
                    match = a;
                    bestLength = r.getLength();
                }
            }
            if (match != null) return match;
        }
        return null;
    }
}
