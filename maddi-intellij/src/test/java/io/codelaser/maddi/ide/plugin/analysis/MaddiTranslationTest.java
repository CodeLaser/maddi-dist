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

package io.codelaser.maddi.ide.plugin.analysis;

import java.util.List;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import io.codelaser.maddi.ide.client.AnalysisModel;

/* What a selection asks the daemon to translate, and how the translator's answer is summed up. */
public class MaddiTranslationTest extends LightJavaCodeInsightFixtureTestCase {

    public void testSelectionOfFiles() {
        // configureByText, not addFileToProject: adding a file wakes the test IDE's Vue.js language service, which
        // fails to initialise there
        VirtualFile a = myFixture.configureByText("A.java", "package p; public class A { } class B { }")
                .getVirtualFile();
        VirtualFile directory = a.getParent(); // not a Java file: no part of the selection
        MaddiTranslation.Selection selection = MaddiTranslation.of(getProject(), null,
                new VirtualFile[]{a, directory});
        assertNotNull(selection);
        assertNull(selection.module());
        assertEquals(List.of("p.A", "p.B"), selection.types());
        assertNull(MaddiTranslation.of(getProject(), null, new VirtualFile[]{directory}));
    }

    public void testSummaryAndReport() {
        AnalysisModel.TranslateResult result = new AnalysisModel.TranslateResult("t",
                List.of(new AnalysisModel.TranslatedFile("p.A", "p/A.kt", "class A")),
                List.of(new AnalysisModel.TranslationMessage("ERROR", "LABELED_BLOCK", "p.A", 7, 3, "a label"),
                        new AnalysisModel.TranslationMessage("INFO", "RANGE", "p.A", 9, -1, "")),
                List.of(), List.of("p.Gone"));
        assertEquals("2 translator message(s) (1 ERROR, 1 INFO); 1 type(s) not analysed",
                MaddiTranslation.summary(result));
        assertEquals(List.of("1 file(s); 2 translator message(s) (1 ERROR, 1 INFO); 1 type(s) not analysed",
                "not analysed: p.Gone", "ERROR LABELED_BLOCK p.A:7 a label", "INFO RANGE p.A:9"),
                MaddiTranslation.report(result));
    }
}
