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
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.codemining.AbstractCodeMiningProvider;
import org.eclipse.jface.text.codemining.ICodeMining;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Renders maddi's computed annotations inline in the Java editor, the Eclipse counterpart of the IntelliJ
 * plugin's declarative inlay hints. Each analyzed declaration gets a {@link MaddiHintCodeMining} carrying
 * its annotations ({@code @Container}, {@code @NotModified}, …) under the shared {@link HintFilter}.
 * <p>
 * This is the public code-mining path: the provider is contributed to
 * {@code org.eclipse.ui.workbench.texteditor.codeMiningProviders} and installed by
 * {@code AbstractTextEditor.installCodeMiningProviders()}, which reads that registry. JDT's own
 * references/implementations minings are registered through the very same extension point, so nothing here
 * depends on JDT internals. Two consequences of living in JDT's editor, though:
 * <ul>
 *   <li>JDT gates the whole mechanism on <em>Preferences &gt; Java &gt; Editor &gt; Code Minings &gt; Enable
 *       code minings</em>. With that off, no third-party minings render either — including these.</li>
 *   <li>Minings are re-queried on document change by JDT's reconciler. An analysis run changes no document,
 *       so {@link MaddiCodeMiningRefresher} pushes the refresh when a new result lands.</li>
 * </ul>
 * Results come from the last whole-project analysis ({@link MaddiResults}); this provider never triggers one,
 * so typing shows stale hints until the next analysis rather than blocking the editor.
 */
public class MaddiCodeMiningProvider extends AbstractCodeMiningProvider {

    /** The one element kind whose hint has to be drawn inline; see {@link #mining}. */
    private static final String PARAMETER = "PARAMETER";

    @Override
    public CompletableFuture<List<? extends ICodeMining>> provideCodeMinings(ITextViewer viewer,
                                                                             IProgressMonitor monitor) {
        return CompletableFuture.completedFuture(minings(viewer));
    }

    /**
     * Computed synchronously: the result is already in memory, so this is a filter over a list, not analysis.
     * Returning an incomplete future here would only add latency and a cancellation path to maintain.
     */
    private List<ICodeMining> minings(ITextViewer viewer) {
        AnalysisModel.Result result = MaddiResults.get().latest();
        if (result == null || result.elementAnnotations() == null) return List.of();
        IDocument document = viewer.getDocument();
        if (document == null) return List.of();
        List<AnalysisModel.ElementAnnotation> elements;
        IClassFile classFile = classFile();
        if (classFile != null) {
            // a library's source: its elements come from the daemon, matched by signature (MaddiLibrary); its ruler
            // icons are annotations rather than markers, so they are placed here too, hints shown or not
            elements = MaddiLibrary.get().placed(classFile, document);
            MaddiLibraryRuler.show(getAdapter(ITextEditor.class), document, elements);
        } else {
            Path editedFile = editedFile();
            if (editedFile == null) return List.of();
            elements = result.elementAnnotations().stream()
                    .filter(element -> editedFile.equals(MaddiDocumentPositions.toPath(element.uri()))).toList();
        }
        if (!MaddiPreferences.inlineHints()) return List.of();

        HintFilter filter = MaddiPreferences.hintFilter();
        List<ICodeMining> minings = new ArrayList<>();
        for (AnalysisModel.ElementAnnotation element : elements) {
            String text = filter.textFor(element);
            if (text.isEmpty()) continue; // nothing to show for this declaration under the current filter
            ICodeMining mining = mining(document, element, text);
            if (mining != null) minings.add(mining); // null: the document moved on; skip rather than guess
        }
        return minings;
    }

    /**
     * Where a hint goes depends on what it annotates and on the {@link HintPlacement} preference. A
     * parameter's annotation only makes sense next to the parameter, so it is always drawn inline;
     * everything else follows the preference, defaulting to its own line above the declaration.
     */
    private ICodeMining mining(IDocument document, AnalysisModel.ElementAnnotation element, String text) {
        if (PARAMETER.equals(element.kind()) || MaddiPreferences.hintPlacement() == HintPlacement.INLINE) {
            Position position = MaddiDocumentPositions.position(document, element.beginLine(),
                    element.beginCol());
            // the separator matters: the label is drawn immediately before the parameter's own text
            return position == null ? null : new MaddiHintCodeMining(position, this, text + ' ');
        }
        Integer line = element.beginLine();
        if (line == null || line < 1 || line > document.getNumberOfLines()) return null;
        try {
            return new MaddiHintLineHeaderCodeMining(line - 1, document, this, text);
        } catch (BadLocationException e) {
            return null;
        }
    }

    /** The library class file this editor is showing (with its attached source), or null. */
    private IClassFile classFile() {
        ITextEditor editor = getAdapter(ITextEditor.class);
        IEditorInput input = editor == null ? null : editor.getEditorInput();
        return input == null ? null : input.getAdapter(IClassFile.class);
    }

    /** The file this editor is showing, as an absolute path, or null when it is not a workspace file. */
    private Path editedFile() {
        ITextEditor editor = getAdapter(ITextEditor.class);
        if (editor == null) return null;
        IEditorInput input = editor.getEditorInput();
        if (input == null) return null;
        IFile file = input.getAdapter(IFile.class);
        if (file == null || file.getLocation() == null) return null;
        return file.getLocation().toFile().toPath();
    }

}
