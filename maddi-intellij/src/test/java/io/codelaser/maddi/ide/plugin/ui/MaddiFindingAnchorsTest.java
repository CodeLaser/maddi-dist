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

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.codelaser.maddi.ide.client.AnalysisModel;

import java.util.List;

/*
 A finding moves with its statement once anchored: splitting the class's brace onto a line of its own moves the
 statement down a line, and the error with it. Not anchored (the text was not the analysed one), it keeps the
 analysis's position, as before.
 */
public class MaddiFindingAnchorsTest extends BasePlatformTestCase {

    private static final String SOURCE = """
            package p;
            public class Circle {
                private static int cntCalls;
                public double area() { ++cntCalls; return 1.0; }
            }
            """;

    // '++cntCalls' on line 4, columns 28-37, as the daemon writes positions (1-based, end inclusive)
    private static final AnalysisModel.Finding FINDING = new AnalysisModel.Finding("/p/Circle.java", 4, 28, 4, 37,
            "ERROR", "contract-violation", "Circle.area() assigns static field 'Circle.cntCalls'", List.of());

    private static String text(Document doc, TextRange range) {
        return doc.getText(range);
    }

    private void splitBrace(Document doc) {
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
                doc.replaceString(doc.getText().indexOf(" {"), doc.getText().indexOf(" {") + 2, "\n{"));
    }

    public void testAnchoredFindingFollowsTheEdit() {
        myFixture.configureByText("Circle.java", SOURCE);
        Document doc = myFixture.getEditor().getDocument();
        MaddiFindingAnchors anchors = new MaddiFindingAnchors();

        TextRange before = anchors.range(FINDING, doc, true);
        assertNotNull(before);
        assertEquals("++cntCalls", text(doc, before));

        splitBrace(doc);
        // now the text is no longer the analysed one; the anchor carries the finding along
        TextRange after = anchors.range(FINDING, doc, false);
        assertNotNull(after);
        assertEquals("++cntCalls", text(doc, after));
        assertEquals(4, doc.getLineNumber(after.getStartOffset())); // 0-based: line 5

        // its text deleted: nothing to show it on
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
                doc.deleteString(doc.getLineStartOffset(4), doc.getLineEndOffset(4)));
        assertNull(anchors.range(FINDING, doc, false));

        // a new result: anchored afresh
        anchors.clear();
        assertNotNull(anchors.range(FINDING, doc, false));
    }

    public void testNotAnchoredKeepsThePosition() {
        myFixture.configureByText("Circle.java", SOURCE);
        Document doc = myFixture.getEditor().getDocument();
        MaddiFindingAnchors anchors = new MaddiFindingAnchors();
        splitBrace(doc);
        // first shown after the edit: the analysed positions are all there is, and they point a line too high
        TextRange range = anchors.range(FINDING, doc, false);
        assertNotNull(range);
        assertEquals(3, doc.getLineNumber(range.getStartOffset()));
    }
}
