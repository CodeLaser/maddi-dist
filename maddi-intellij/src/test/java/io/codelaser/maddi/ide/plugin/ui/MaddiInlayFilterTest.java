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

import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase;
import io.codelaser.maddi.ide.plugin.analysis.MaddiAnalysisService;
import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.ide.plugin.settings.HintPlacement;
import io.codelaser.maddi.ide.plugin.settings.InlineHintsMode;
import io.codelaser.maddi.ide.plugin.settings.MaddiSettings;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Live inlay-filter test: renders {@link MaddiInlayProvider} through the real declarative-inlay pass and
 * asserts that the {@link InlineHintsMode} filter decides which annotations appear inline. A canned result
 * (three annotations spanning the polarity/context-default axes) is injected into {@link MaddiAnalysisService}
 * via the daemon-free {@code applyResult} seam; the gutter is unaffected (it always shows the full set).
 */
public class MaddiInlayFilterTest extends DeclarativeInlayHintsProviderTestCase {

    // Clean source; the fixture is configured with this, then hints are dumped into it and compared.
    private static final String SOURCE = """
            public class Box {
                public int get() { return 0; }
                public void set(Mutable m) { }
            }
            """;

    /** @Container on the type — POSITIVE, informative (proven, not context-implied). */
    private static final AnalysisModel.Annotation CONTAINER =
            new AnalysisModel.Annotation("@Container", "POSITIVE", false);
    /** @NotModified on a read-only method — POSITIVE but a context default (implied by @Container). */
    private static final AnalysisModel.Annotation NOT_MODIFIED =
            new AnalysisModel.Annotation("@NotModified", "POSITIVE", true);
    /** @Modified on the parameter — NEGATIVE and NOT a context default (the signal, always worth showing). */
    private static final AnalysisModel.Annotation MODIFIED =
            new AnalysisModel.Annotation("@Modified", "NEGATIVE", false);

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return LightJavaCodeInsightFixtureTestCase.JAVA_LATEST;
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            MaddiSettings.State state = MaddiSettings.getInstance().getState();
            state.inlineHintsMode = InlineHintsMode.HIDE_CONTEXT_DEFAULTS;
            state.hintPlacement = HintPlacement.ABOVE_DECLARATION;
        } finally {
            super.tearDown();
        }
    }

    public void testAllShowsEverything() {
        doFilterTest(InlineHintsMode.ALL, """
                public class Box/*<# @Container #>*/ {
                    public int get/*<# @NotModified #>*/() { return 0; }
                    public void set(/*<# @Modified #>*/Mutable m) { }
                }
                """);
    }

    public void testHideContextDefaultsDropsImpliedPositive() {
        // @NotModified (context default) is dropped; the informative @Container and the @Modified signal stay.
        doFilterTest(InlineHintsMode.HIDE_CONTEXT_DEFAULTS, """
                public class Box/*<# @Container #>*/ {
                    public int get() { return 0; }
                    public void set(/*<# @Modified #>*/Mutable m) { }
                }
                """);
    }

    public void testPositiveOnlyDropsTheModifiedSignal() {
        doFilterTest(InlineHintsMode.POSITIVE_ONLY, """
                public class Box/*<# @Container #>*/ {
                    public int get/*<# @NotModified #>*/() { return 0; }
                    public void set(Mutable m) { }
                }
                """);
    }

    public void testNegativeOnlyKeepsOnlyTheModifiedSignal() {
        doFilterTest(InlineHintsMode.NEGATIVE_ONLY, """
                public class Box {
                    public int get() { return 0; }
                    public void set(/*<# @Modified #>*/Mutable m) { }
                }
                """);
    }

    /**
     * ⛔ THE {@code after=} ROSTER DOES NOT FIT ON THE ONE LINE AN INLINE HINT HAS. It is a union of marks and
     * unbounded — 16 on one of maddi's own types, 60 on {@code Element} per the dogfood baseline — so the
     * inline form states that the verdict is eventual and how far off, and the gutter tooltip carries the
     * roster. See {@code AnalysisModel#abbreviate}.
     */
    public void testEventualRosterIsAbbreviatedInline() {
        myFixture.configureByText("Box.java", SOURCE);
        String path = myFixture.getFile().getVirtualFile().getPath();

        AnalysisModel.Annotation eventual = new AnalysisModel.Annotation(
                "@Immutable(hc=true,after=\"a,b,c,d,e,f\")", "EVENTUAL", false);
        AnalysisModel.ElementAnnotation type = new AnalysisModel.ElementAnnotation(
                path, 1, 1, 4, 2, "TYPE", "Box", List.of(eventual.text()), List.of(eventual), Map.of());
        MaddiAnalysisService.getInstance(getProject()).applyResult(new AnalysisModel.Result(
                "test", List.of(), List.of(type), List.of(), 0, 0, 0, AnalysisModel.OUTCOME_CERTIFIED));

        MaddiSettings.State state = MaddiSettings.getInstance().getState();
        state.inlineHintsMode = InlineHintsMode.ALL;   // and it survives even "show everything"
        state.hintPlacement = HintPlacement.INLINE;

        doTestProviderWithConfigured(SOURCE, """
                public class Box/*<# @Immutable(hc=true,after=6) #>*/ {
                    public int get() { return 0; }
                    public void set(Mutable m) { }
                }
                """, new MaddiInlayProvider(), Map.of(), null, false,
                DeclarativeInlayHintsProviderTestCase.ProviderTestMode.SIMPLE);
    }

    private void doFilterTest(InlineHintsMode mode, String expected) {
        myFixture.configureByText("Box.java", SOURCE);
        String path = myFixture.getFile().getVirtualFile().getPath();

        AnalysisModel.ElementAnnotation type = new AnalysisModel.ElementAnnotation(
                path, 1, 1, 4, 2, "TYPE", "Box",
                List.of(CONTAINER.text()), List.of(CONTAINER), Map.of());
        AnalysisModel.ElementAnnotation method = new AnalysisModel.ElementAnnotation(
                path, 2, 1, 2, 40, "METHOD", "Box.get",
                List.of(NOT_MODIFIED.text()), List.of(NOT_MODIFIED), Map.of());
        AnalysisModel.ElementAnnotation param = new AnalysisModel.ElementAnnotation(
                path, 3, 1, 3, 40, "PARAMETER", "Box.set.m",
                List.of(MODIFIED.text()), List.of(MODIFIED), Map.of());
        AnalysisModel.Result result = new AnalysisModel.Result(
                "test", List.of(), List.of(type, method, param), List.of(), 0, 0, 0, AnalysisModel.OUTCOME_CERTIFIED);
        MaddiAnalysisService.getInstance(getProject()).applyResult(result);

        MaddiSettings.State state = MaddiSettings.getInstance().getState();
        state.inlineHintsMode = mode;
        // this suite is about the filter, not the placement: pin every hint inline so the expectations
        // read as one annotated line per declaration. Placement itself is MaddiInlayPlacementTest.
        state.hintPlacement = HintPlacement.INLINE;

        doTestProviderWithConfigured(SOURCE, expected, new MaddiInlayProvider(), Map.of(), null, false,
                DeclarativeInlayHintsProviderTestCase.ProviderTestMode.SIMPLE);
    }
}
