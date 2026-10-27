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

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Position;

import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * maddi's coordinates to Eclipse's, the counterpart of the IntelliJ plugin's {@code MaddiPositions}.
 * <p>
 * The editor is live while an analysis result is not: the user keeps typing after the run that produced it.
 * Every conversion here therefore returns null rather than throwing when the result no longer fits the
 * document — a stale hint is dropped, never rendered at a wrong or out-of-bounds place.
 */
public final class MaddiDocumentPositions {

    private MaddiDocumentPositions() {
    }

    /**
     * maddi's 1-based line/column as a document position, or null if it no longer fits.
     *
     * @param column may be null, or past the end of the line after an edit; it is clamped onto the line
     */
    public static Position position(IDocument document, Integer line, Integer column) {
        if (document == null || line == null || line < 1) return null;
        int lineIndex = line - 1;
        if (lineIndex >= document.getNumberOfLines()) return null;
        try {
            // getLineInformation, not getLineLength: the latter counts the line delimiter, so clamping
            // against it would put the hint on the newline rather than on the last real character
            IRegion region = document.getLineInformation(lineIndex);
            int within = column == null || column < 1
                    ? 0 : Math.min(column - 1, Math.max(region.getLength() - 1, 0));
            return new Position(region.getOffset() + within, 1);
        } catch (BadLocationException e) {
            return null;
        }
    }

    /**
     * The absolute path behind a maddi {@code file:///abs/File.java} URI, or null if it is not a readable
     * file URI. Callers compare paths rather than URI strings: Eclipse spells the same file {@code file:/abs/…}
     * and maddi {@code file:///abs/…}, which are unequal as strings and as {@link URI}s.
     */
    public static Path toPath(String uri) {
        if (uri == null) return null;
        try {
            return Paths.get(URI.create(uri));
        } catch (IllegalArgumentException | FileSystemNotFoundException e) {
            return null;
        }
    }
}
