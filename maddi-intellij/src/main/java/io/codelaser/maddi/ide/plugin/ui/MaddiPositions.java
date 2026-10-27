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
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.Nullable;

/** Converts maddi's 1-based (line, column) positions into IntelliJ document offsets / ranges. */
public final class MaddiPositions {
    private MaddiPositions() {
    }

    /** Offset of a 1-based (line, col) in the document, clamped to the line. Returns -1 if out of range. */
    public static int offset(Document doc, int line1, int col1) {
        int line = line1 - 1;
        if (line < 0 || line >= doc.getLineCount()) return -1;
        int lineStart = doc.getLineStartOffset(line);
        int lineEnd = doc.getLineEndOffset(line);
        int off = lineStart + Math.max(0, col1 - 1);
        return Math.min(off, lineEnd);
    }

    /**
     * Range from maddi begin/end (1-based; endCol inclusive). Returns null if positions are missing or invalid.
     * The end offset is exclusive (one past the last covered character).
     */
    public static @Nullable TextRange range(Document doc, Integer beginLine, Integer beginCol,
                                            Integer endLine, Integer endCol) {
        if (beginLine == null || beginCol == null) return null;
        int start = offset(doc, beginLine, beginCol);
        if (start < 0) return null;
        int end;
        if (endLine == null || endCol == null) {
            end = start + 1;
        } else {
            end = offset(doc, endLine, endCol + 1); // +1: endCol is inclusive, make it exclusive
        }
        if (end < 0 || end <= start) end = Math.min(start + 1, doc.getTextLength());
        if (start >= doc.getTextLength()) return null;
        return new TextRange(start, Math.min(end, doc.getTextLength()));
    }
}
