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
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The ruler icons of a library file. A project file's come from markers ({@link MaddiMarkers}), which need a
 * workspace resource that a class file does not have; so here the same annotation types go straight into the
 * editor's annotation model, with the same hover text and, under it, where the values come from: our annotated API,
 * or the default for want of an annotation.
 */
final class MaddiLibraryRuler {
    private static final String HINT = MaddiEclipsePlugin.PLUGIN_ID + ".analysisHintAnnotation";
    private static final String HINT_EVENTUAL = MaddiEclipsePlugin.PLUGIN_ID + ".analysisHintEventualAnnotation";
    private static final Set<String> OURS = Set.of(HINT, HINT_EVENTUAL);

    private MaddiLibraryRuler() {
    }

    static void show(ITextEditor editor, IDocument document, List<AnalysisModel.ElementAnnotation> elements) {
        if (editor == null) return;
        List<Annotation> annotations = new ArrayList<>();
        List<Position> positions = new ArrayList<>();
        if (MaddiPreferences.showGutterIcons()) {
            for (AnalysisModel.ElementAnnotation element : elements) {
                String text = MaddiMarkers.gutterText(element, elements);
                Position position = MaddiDocumentPositions.position(document, element.beginLine(), element.beginCol());
                if (text == null || position == null) continue;
                String note = element.properties() == null ? null : element.properties().get(AnalysisModel.LIBRARY_NOTE);
                annotations.add(new Annotation(AnalysisModel.isEventual(element) ? HINT_EVENTUAL : HINT, false,
                        note == null ? text : text + "\n" + note));
                positions.add(position);
            }
        }
        Display display = Display.getDefault();
        if (display.isDisposed()) return;
        display.asyncExec(() -> {
            IDocumentProvider provider = editor.getDocumentProvider();
            IAnnotationModel model = provider == null ? null : provider.getAnnotationModel(editor.getEditorInput());
            if (model == null) return;
            for (Iterator<Annotation> it = model.getAnnotationIterator(); it.hasNext(); ) {
                Annotation annotation = it.next();
                if (OURS.contains(annotation.getType())) model.removeAnnotation(annotation);
            }
            for (int i = 0; i < annotations.size(); i++) model.addAnnotation(annotations.get(i), positions.get(i));
        });
    }
}
