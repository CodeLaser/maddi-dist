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

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.util.TextRange;
import io.codelaser.maddi.ide.client.AnalysisModel;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Where a finding is in the document now. A finding sits on a statement, which has no name to be found again by as a
 * declaration's hints are; so it is anchored instead: a range marker, which the editor moves with every edit, made on
 * the finding's first showing, provided the document then still holds the text the analysis read. Before that, or
 * when it no longer does, the finding has only the analysis's positions, as it used to: they hold until an edit moves
 * the lines. One set per result: a new result's findings are anchored afresh.
 */
public final class MaddiFindingAnchors {
    // by identity: two findings can be equal records (the same message at the same place, twice)
    private final Map<AnalysisModel.Finding, RangeMarker> anchors = new IdentityHashMap<>();

    /** A new result: its findings are other objects, and these markers would only keep their documents alive. */
    public synchronized void clear() {
        anchors.values().forEach(RangeMarker::dispose);
        anchors.clear();
    }

    /**
     * The finding's range in {@code doc}; {@code null} when it has none there (its text was deleted, or its position
     * is past the document's end). {@code asAnalysed}: the document holds the text the analysis read, so the
     * positions are right and can be anchored.
     */
    public synchronized @Nullable TextRange range(AnalysisModel.Finding finding, Document doc, boolean asAnalysed) {
        RangeMarker marker = anchors.get(finding);
        if (marker != null && marker.getDocument() == doc) return marker.isValid() ? marker.getTextRange() : null;
        TextRange range = MaddiPositions.range(doc, finding.beginLine(), finding.beginCol(), finding.endLine(),
                finding.endCol());
        if (range == null || !asAnalysed) return range;
        if (marker != null) marker.dispose();
        anchors.put(finding, doc.createRangeMarker(range));
        return range;
    }
}
