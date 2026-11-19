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

package io.codelaser.maddi.ide.eclipse.uitests;

import io.codelaser.maddi.ide.eclipse.MaddiMarkers;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.inlined.AbstractInlinedAnnotation;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import static io.codelaser.maddi.ide.eclipse.uitests.Workbench.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One analysis of the fixture project from the Java editor, and what the editor then shows: the hints (code minings),
 * the ruler icons (hint markers), the near miss in the Problems view, and hints that stay on their declarations while
 * one edits above them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MaddiInTheEditorTest {

    private ITextEditor point;

    @BeforeAll
    public void analyze() throws Exception {
        demoProject();
        configure(false);
        point = open("Point.java");
        Workbench.analyze(point);
    }

    @Test
    @Order(1)
    @DisplayName("shows an immutable type as such")
    public void immutableType() {
        List<Hint> hints = hints(point);
        List<String> labels = labelsOn(hints, lineOf(point, "public final class Point"));
        assertTrue(labels.stream().anyMatch(l -> l.contains("@Immutable")), () -> "hints: " + hints);
    }

    @Test
    @Order(2)
    @DisplayName("shows a modifying method")
    public void modifyingMethod() {
        ITextEditor counter = open("Counter.java");
        List<Hint> hints = hints(counter);
        List<String> labels = labelsOn(hints, lineOf(counter, "public void increment()"));
        assertTrue(labels.stream().anyMatch(l -> l.contains("@Modified")), () -> "hints: " + hints);
    }

    @Test
    @Order(3)
    @DisplayName("reports the near miss in the Problems view, as information")
    public void nearMiss() throws Exception {
        IMarker[] markers = file("Names.java").findMarkers(MaddiMarkers.VIOLATION_TYPE, true, IResource.DEPTH_ZERO);
        Pattern container = Pattern.compile("container", Pattern.CASE_INSENSITIVE);
        assertTrue(Arrays.stream(markers).anyMatch(m ->
                        container.matcher(m.getAttribute(IMarker.MESSAGE, "")).find()
                        && m.getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_INFO),
                () -> "markers: " + Arrays.stream(markers).map(m -> m.getAttribute(IMarker.MESSAGE, "")).toList());
    }

    @Test
    @Order(4)
    @DisplayName("puts a ruler icon on the immutable type")
    public void rulerIcon() throws Exception {
        int line = lineOf(point, "public final class Point") + 1;
        IMarker[] markers = file("Point.java").findMarkers(MaddiMarkers.HINT_TYPE, true, IResource.DEPTH_ZERO);
        assertTrue(Arrays.stream(markers).anyMatch(m -> m.getAttribute(IMarker.LINE_NUMBER, -1) == line
                                                        && m.getAttribute(IMarker.MESSAGE, "").contains("@Immutable")),
                () -> "markers: " + Arrays.stream(markers).map(m -> m.getAttribute(IMarker.LINE_NUMBER, -1) + " "
                        + m.getAttribute(IMarker.MESSAGE, "")).toList());
    }

    @Test
    @Order(5)
    @DisplayName("the Java editor draws the hints")
    public void editorDrawsThem() {
        // the minings are drawn as inlined annotations in the viewer; JDT's own minings are off by default, so
        // every one there is maddi's. They arrive asynchronously after the refresh the run triggers.
        long deadline = System.currentTimeMillis() + 30_000;
        int drawn = 0;
        while ((drawn = inlinedAnnotations(point)) == 0 && System.currentTimeMillis() < deadline) sleep(250);
        assertTrue(drawn > 0, "the editor draws no code minings");
    }

    @Test
    @Order(6)
    @DisplayName("keeps the hints on their declarations while one types above them")
    public void hintsFollowEdits() {
        ITextEditor counter = open("Counter.java");
        List<Hint> before = hints(counter);
        assertFalse(before.isEmpty(), "no hints to move");
        IDocument document = document(counter);
        int offset = ui(() -> document.getLineOffset(lineOf(counter, "public class Counter")));
        try {
            ui(() -> {
                document.replace(offset, 0, "// one\n// two\n");
                return null;
            });
            List<Hint> after = hints(counter);
            assertEquals(before.stream().map(h -> new Hint(h.line() + 2, h.column(), h.label())).toList(), after);
        } finally {
            ui(() -> {
                counter.doRevertToSaved();
                return null;
            });
        }
    }

    private static int inlinedAnnotations(ITextEditor editor) {
        return ui(() -> {
            ISourceViewer viewer = (ISourceViewer) viewer(editor);
            int count = count(viewer.getAnnotationModel());
            if (viewer instanceof SourceViewer sourceViewer) count += count(sourceViewer.getVisualAnnotationModel());
            return count;
        });
    }

    private static int count(IAnnotationModel model) {
        if (model == null) return 0;
        int count = 0;
        for (Iterator<Annotation> it = model.getAnnotationIterator(); it.hasNext(); ) {
            if (it.next() instanceof AbstractInlinedAnnotation) count++;
        }
        return count;
    }
}
