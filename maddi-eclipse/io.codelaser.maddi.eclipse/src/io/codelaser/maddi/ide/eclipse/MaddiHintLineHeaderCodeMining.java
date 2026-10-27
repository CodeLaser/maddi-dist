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
import org.eclipse.jface.text.codemining.ICodeMiningProvider;
import org.eclipse.jface.text.codemining.LineHeaderCodeMining;

/**
 * The annotations of one declaration, drawn on their own line just above it — the presentation JDT itself
 * uses for its references/implementations minings.
 * <p>
 * Deliberately not a {@link MaddiHintCodeMining} (line content). A line-content mining has to borrow a real
 * character's glyph box to make room for its label, which clips that character and leaves the platform to
 * repaint it by hand; on the paint where the glyph metrics change it returns before doing so, and the
 * character is left showing the mining's own styling. That is Eclipse bug 531769, marked FIXME in
 * {@code InlinedAnnotationDrawingStrategy}, and not fixable from a plugin. A line-header mining has no host
 * character at all, so the question never arises — and a full annotation list reads better above a
 * declaration than wedged in front of it.
 */
public class MaddiHintLineHeaderCodeMining extends LineHeaderCodeMining {

    public MaddiHintLineHeaderCodeMining(int beforeLineNumber, IDocument document, ICodeMiningProvider provider,
                                         String label) throws BadLocationException {
        super(beforeLineNumber, document, provider);
        setLabel(label);
    }
}
