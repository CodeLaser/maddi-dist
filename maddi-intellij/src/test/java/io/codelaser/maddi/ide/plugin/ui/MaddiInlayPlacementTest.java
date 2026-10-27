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
import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.ide.plugin.analysis.MaddiAnalysisService;
import io.codelaser.maddi.ide.plugin.settings.HintPlacement;
import io.codelaser.maddi.ide.plugin.settings.InlineHintsMode;
import io.codelaser.maddi.ide.plugin.settings.MaddiSettings;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Where the hints land. The default ({@link HintPlacement#ABOVE_DECLARATION}) gives each declaration a line
 * of its own, so the result reads like hand-written annotated API source; a parameter's annotations stay
 * inline in both modes, because a parameter has no line of its own to use.
 * <p>
 * Renders through the real declarative-inlay pass: a block hint above a line and an inline one after a name
 * are different {@code InlayPosition} types, and only the platform's own rendering shows which was produced.
 */
public class MaddiInlayPlacementTest extends DeclarativeInlayHintsProviderTestCase {

    private static final String SOURCE = """
            public class Box {
                public int get() { return 0; }
                public void set(Mutable m) { }
            }
            """;

    private static final AnalysisModel.Annotation CONTAINER =
            new AnalysisModel.Annotation("@Container", "POSITIVE", false);
    private static final AnalysisModel.Annotation NOT_MODIFIED =
            new AnalysisModel.Annotation("@NotModified", "POSITIVE", false);
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

    public void testAboveDeclarationPutsTypeAndMethodHintsOnTheirOwnLine() {
        // note the indentation of the method's hint: it lines up with the declaration it describes, which is
        // what AboveLineIndentedPosition buys over a plain block inlay. The parameter stays inline.
        doPlacementTest(HintPlacement.ABOVE_DECLARATION, """
                /*<# block [@Container] #>*/
                public class Box {
                    /*<# block [@NotModified] #>*/
                    public int get() { return 0; }
                    public void set(/*<# @Modified #>*/Mutable m) { }
                }
                """);
    }

    public void testInlinePutsEveryHintAfterTheName() {
        doPlacementTest(HintPlacement.INLINE, """
                public class Box/*<# @Container #>*/ {
                    public int get/*<# @NotModified #>*/() { return 0; }
                    public void set(/*<# @Modified #>*/Mutable m) { }
                }
                """);
    }

    /** A type parameter is a PsiClass to IntelliJ: it must not get its class's hints (maddi-dist#6, Pair<A, B>). */
    public void testTypeParameterGetsNoTypeHint() {
        String source = """
                public class Pair<A, B> {
                }
                """;
        myFixture.configureByText("Pair.java", source);
        String path = myFixture.getFile().getVirtualFile().getPath();
        AnalysisModel.ElementAnnotation type = new AnalysisModel.ElementAnnotation(
                path, 1, 1, 2, 1, "TYPE", "Pair", List.of(CONTAINER.text()), List.of(CONTAINER), Map.of());
        MaddiAnalysisService.getInstance(getProject()).applyResult(new AnalysisModel.Result(
                "test", List.of(), List.of(type), List.of(), 0, 0, 0, AnalysisModel.OUTCOME_CERTIFIED));
        MaddiSettings.State state = MaddiSettings.getInstance().getState();
        state.inlineHintsMode = InlineHintsMode.ALL;
        state.hintPlacement = HintPlacement.INLINE;
        doTestProviderWithConfigured(source, """
                public class Pair/*<# @Container #>*/<A, B> {
                }
                """, new MaddiInlayProvider(), Map.of(), null, false,
                DeclarativeInlayHintsProviderTestCase.ProviderTestMode.SIMPLE);
    }

    private void doPlacementTest(HintPlacement placement, String expected) {
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
        MaddiAnalysisService.getInstance(getProject()).applyResult(new AnalysisModel.Result(
                "test", List.of(), List.of(type, method, param), List.of(), 0, 0, 0,
                AnalysisModel.OUTCOME_CERTIFIED));

        MaddiSettings.State state = MaddiSettings.getInstance().getState();
        state.inlineHintsMode = InlineHintsMode.ALL; // placement is the variable here, not the filter
        state.hintPlacement = placement;

        doTestProviderWithConfigured(SOURCE, expected, new MaddiInlayProvider(), Map.of(), null, false,
                DeclarativeInlayHintsProviderTestCase.ProviderTestMode.SIMPLE);
    }
}
