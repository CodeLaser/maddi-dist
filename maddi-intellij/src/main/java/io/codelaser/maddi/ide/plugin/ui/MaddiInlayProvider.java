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

import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.codeInsight.hints.declarative.AboveLineIndentedPosition;
import com.intellij.codeInsight.hints.declarative.HintFormat;
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector;
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider;
import com.intellij.codeInsight.hints.declarative.InlayPosition;
import com.intellij.codeInsight.hints.declarative.InlayTreeSink;
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition;
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiTypeParameter;

import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.ide.plugin.analysis.MaddiAnalysisService;
import io.codelaser.maddi.ide.plugin.settings.HintPlacement;
import io.codelaser.maddi.ide.plugin.settings.InlineHintsMode;
import io.codelaser.maddi.ide.plugin.settings.MaddiSettings;
import kotlin.Unit;

/**
 * Renders the computed analysis hints ({@code @Immutable}, {@code @Container}, {@code @NotModified},
 * {@code @NotNull}, …) as inlays at each declaration (type/method/field/parameter). Matches a maddi element
 * to a declaration's name identifier by range containment + kind.
 * <p>
 * Declarations get their hints on a line of their own above them by default, so the result reads like
 * hand-written annotated API source; see {@link HintPlacement} for the setting and for why parameters are
 * always inline.
 */
public class MaddiInlayProvider implements InlayHintsProvider {

    @Override
    public @Nullable InlayHintsCollector createCollector(@NotNull PsiFile file, @NotNull Editor editor) {
        MaddiSettings.State settings = MaddiSettings.getInstance().getState();
        InlineHintsMode mode = settings.inlineHintsMode;
        if (mode == InlineHintsMode.NONE) return null;
        Document doc = file.getViewProvider().getDocument();
        if (doc == null) return null;
        // a library file's too (Set.java from the JDK's sources), matched by signature
        List<AnalysisModel.ElementAnnotation> annotations =
                MaddiAnalysisService.getInstance(file.getProject()).annotationsFor(file, doc);
        if (annotations.isEmpty()) return null;
        return new Collector(annotations, doc, mode, settings.hintPlacement, this::kindsOf, this::parameterHintOffset);
    }

    /**
     * The maddi kinds ({@code TYPE}, {@code METHOD}, {@code FIELD}, {@code PARAMETER}), in order of preference, of
     * the declaration whose NAME is this leaf; empty for none. Java's; a language with another PSI overrides it
     * (the Kotlin provider).
     */
    protected List<String> kindsOf(@NotNull PsiElement leaf) {
        String kind = leaf instanceof PsiIdentifier ? javaKindOf(leaf.getParent()) : null;
        return kind == null ? List.of() : List.of(kind);
    }

    /**
     * Where a parameter's hint goes, given its name: where an annotation is written, before the type in Java
     * ({@code final @Nullable String s}). Java's; the Kotlin provider overrides it.
     */
    protected int parameterHintOffset(@NotNull PsiElement leaf) {
        if (leaf.getParent() instanceof PsiParameter parameter) {
            return parameter.getTypeElement() != null ? parameter.getTypeElement().getTextRange().getStartOffset()
                    : parameter.getTextRange().getStartOffset();
        }
        return leaf.getTextRange().getStartOffset();
    }

    private static final String PARAMETER = "PARAMETER";

    private static final class Collector implements SharedBypassCollector {
        private final List<AnalysisModel.ElementAnnotation> annotations;
        private final Document doc;
        private final InlineHintsMode mode;
        private final HintPlacement placement;
        private final Function<PsiElement, List<String>> kinds;
        private final ToIntFunction<PsiElement> parameterOffset;

        Collector(List<AnalysisModel.ElementAnnotation> annotations, Document doc, InlineHintsMode mode,
                  HintPlacement placement, Function<PsiElement, List<String>> kinds,
                  ToIntFunction<PsiElement> parameterOffset) {
            this.annotations = annotations;
            this.doc = doc;
            this.mode = mode;
            this.placement = placement;
            this.kinds = kinds;
            this.parameterOffset = parameterOffset;
        }

        @Override
        public void collectFromElement(@NotNull PsiElement element, @NotNull InlayTreeSink sink) {
            List<String> wanted = kinds.apply(element);
            if (wanted.isEmpty()) return;
            AnalysisModel.ElementAnnotation match = MaddiElementMatch.find(annotations, doc, wanted,
                    element.getTextRange().getStartOffset(), a -> !a.annotations().isEmpty());
            if (match == null) return;
            String kind = wanted.getFirst();
            // abbreviated: an eventual verdict's after= roster is a union and runs to dozens of marks, which
            // does not fit on the one line an inline hint has. The gutter tooltip carries the full text.
            String text = match.annotations().stream()
                    .filter(mode::shows)
                    .map(AnalysisModel.Annotation::text)
                    .map(AnalysisModel::abbreviate)
                    .collect(Collectors.joining(" "));
            if (text.isEmpty()) return; // everything filtered out under the current mode
            sink.addPresentation(
                    position(element, kind),
                    null,
                    null,
                    HintFormat.Companion.getDefault(),
                    builder -> {
                        builder.text(text, null);
                        return Unit.INSTANCE;
                    });
        }

        /**
         * A parameter's annotations always go where they would be written, before the parameter's type (Java) or
         * the parameter (Kotlin); for everything else the placement is a setting, defaulting to a line of its own
         * above the declaration (see {@link HintPlacement}).
         */
        private InlayPosition position(PsiElement identifier, String kind) {
            if (PARAMETER.equals(kind)) {
                return new InlineInlayPosition(parameterOffset.applyAsInt(identifier), false, 0);
            }
            if (placement == HintPlacement.INLINE) {
                return new InlineInlayPosition(identifier.getTextRange().getEndOffset(), true, 0);
            }
            // anchored on the identifier: the hint lands above that line, indented to match the declaration
            return new AboveLineIndentedPosition(identifier.getTextRange().getStartOffset(), 0, 0);
        }
    }

    private static @Nullable String javaKindOf(PsiElement parent) {
        // a type parameter is a PsiClass too: 'Pair<A, B>' showed the class's verdict three times (maddi-dist#6)
        if (parent instanceof PsiClass && !(parent instanceof PsiTypeParameter)) return "TYPE";
        if (parent instanceof PsiMethod) return "METHOD";
        if (parent instanceof PsiField) return "FIELD";
        if (parent instanceof PsiParameter) return "PARAMETER";
        return null;
    }
}
