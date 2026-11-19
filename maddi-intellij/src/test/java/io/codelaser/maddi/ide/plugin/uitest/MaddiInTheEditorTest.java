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

package io.codelaser.maddi.ide.plugin.uitest;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;

/**
 * One analysis of the fixture project from the editor, and what the editor then shows: the inlay hints, the gutter
 * icon, the near miss as a weak warning, and hints that stay on their declarations while one types above them. One
 * test method, one project and one daemon run: each heavy test sets up a project of its own.
 */
public class MaddiInTheEditorTest extends MaddiIdeTestCase {

    public void testWhatTheEditorShowsAfterAnAnalysis() {
        analyze("Point.java");

        // an immutable type, as such
        List<Hint> point = hints("Point.java");
        assertTrue("hints: " + point, labelsOn(point, lineOf("Point.java", "public final class Point")).stream()
                .anyMatch(l -> l.contains("@Immutable")));

        // a modifying method
        List<Hint> counter = hints("Counter.java");
        assertTrue("hints: " + counter, labelsOn(counter, lineOf("Counter.java", "public void increment()")).stream()
                .anyMatch(l -> l.contains("@Modified")));

        // the near miss, as the weakest warning
        open("Names.java");
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        Pattern container = Pattern.compile("container", Pattern.CASE_INSENSITIVE);
        assertTrue("highlights: " + highlights.stream().map(HighlightInfo::getDescription).toList(),
                highlights.stream().anyMatch(h -> h.getDescription() != null
                                                  && container.matcher(h.getDescription()).find()
                                                  && h.getSeverity() == HighlightSeverity.WEAK_WARNING));

        // a gutter icon on the immutable type
        open("Point.java");
        myFixture.doHighlighting();
        List<String> tooltips = myFixture.findAllGutters().stream().map(GutterMark::getTooltipText)
                .filter(Objects::nonNull).toList();
        assertTrue("gutter: " + tooltips, tooltips.stream().anyMatch(t -> t.contains("@Immutable")));

        // hints that follow an edit above them, before any new analysis
        List<Hint> before = hints("Counter.java");
        assertFalse("no hints to move", before.isEmpty());
        Document document = myFixture.getEditor().getDocument();
        int offset = document.getLineStartOffset(lineOf("Counter.java", "public class Counter"));
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(offset, "// one\n// two\n"));
        List<Hint> after = hints("Counter.java");
        assertEquals(before.stream().map(h -> new Hint(h.line() + 2, h.column(), h.label())).toList(), after);
    }
}
