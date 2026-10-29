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

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import io.codelaser.maddi.ide.plugin.analysis.MaddiAnalysisService;
import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.ide.plugin.settings.MaddiSettings;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A gutter icon next to each analyzed type/method/field carrying computed annotations; the tooltip
 * shows the full set ({@code @Immutable}, {@code @Container}, {@code @NotModified}, …). Anchors on the
 * declaration's name identifier (a leaf), matched to the maddi element by range containment + kind.
 */
public class MaddiLineMarkerProvider implements LineMarkerProvider {

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(PsiElement element) {
        if (!MaddiSettings.getInstance().getState().showGutterIcons) return null;
        List<String> kinds = kindsOf(element);
        // parameters are covered by inlay hints, not the gutter
        if (kinds.isEmpty() || kinds.contains("PARAMETER")) return null;

        PsiFile file = element.getContainingFile();
        VirtualFile vf = file == null ? null : file.getVirtualFile();
        if (vf == null) return null;
        List<AnalysisModel.ElementAnnotation> annotations =
                MaddiAnalysisService.getInstance(element.getProject()).annotationsForPath(vf.getPath());
        if (annotations.isEmpty()) return null;
        Document doc = file.getViewProvider().getDocument();
        if (doc == null) return null;

        // a method shows its parameters' annotations too: a parameter has no gutter mark of its own, and its inline
        // hint is abbreviated, or gone when the parameter is on a line of its own after an edit
        AnalysisModel.ElementAnnotation match = MaddiElementMatch.find(annotations, doc, kinds,
                element.getTextRange().getStartOffset(), a -> !a.displayAnnotations().isEmpty()
                                                              || !parameters(annotations, a).isEmpty());
        if (match == null) return null;
        List<AnalysisModel.ElementAnnotation> parameters = parameters(annotations, match);

        // The gutter keeps the FULL text, roster and all: it is the surface with room for it, and the inline
        // hint deliberately abbreviates (AnalysisModel#abbreviate).
        String text = String.join(" ", match.displayAnnotations());
        String tooltip = "maddi: " + text;
        String accessible = "maddi analysis: " + text;
        if (!parameters.isEmpty() || !match.whys().isEmpty()) {
            StringBuilder html = new StringBuilder("<html>maddi: ").append(StringUtil.escapeXmlEntities(text));
            StringBuilder plain = new StringBuilder(accessible);
            appendWhys(match, html, plain, "&nbsp;&nbsp;");
            for (AnalysisModel.ElementAnnotation p : parameters) {
                String name = p.fqn().substring(p.fqn().lastIndexOf(':') + 1);
                String ofParameter = String.join(" ", p.displayAnnotations());
                html.append("<br>&nbsp;&nbsp;").append(StringUtil.escapeXmlEntities(name)).append(": ")
                        .append(StringUtil.escapeXmlEntities(ofParameter));
                plain.append("; ").append(name).append(": ").append(ofParameter);
                appendWhys(p, html, plain, "&nbsp;&nbsp;&nbsp;&nbsp;");
            }
            tooltip = html.append("</html>").toString();
            accessible = plain.toString();
        }
        // ⛔ AN EVENTUAL VERDICT AND A PLAIN ONE LOOKED IDENTICAL. Polarity drove filtering only, so
        // "@Immutable" and "@Immutable(hc=true,after=…)" -- a proven property and one that holds only after a
        // mark -- reached the gutter under the same icon, and the difference was legible only by reading the
        // tooltip to its end. It is the distinction the whole eventual family exists to make.
        javax.swing.Icon icon = AnalysisModel.isEventual(match) || parameters.stream().anyMatch(AnalysisModel::isEventual)
                ? AllIcons.Nodes.Static : AllIcons.Nodes.Annotationtype;
        String tooltipText = tooltip;
        String accessibleName = accessible;
        return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                icon,
                e -> tooltipText,
                null,
                GutterIconRenderer.Alignment.LEFT,
                () -> accessibleName);
    }

    /**
     * Each annotation's why, a chain of statements: the element's own first, then into the callees. A step in the
     * element's own file is "line n", one elsewhere "File.java:n".
     */
    private static void appendWhys(AnalysisModel.ElementAnnotation element, StringBuilder html, StringBuilder plain,
                                   String indent) {
        for (AnalysisModel.Why why : element.whys()) {
            if (why.steps() == null || why.steps().isEmpty()) continue;
            html.append("<br>").append(indent).append('@').append(StringUtil.escapeXmlEntities(why.annotation()))
                    .append(':');
            plain.append("; @").append(why.annotation()).append(':');
            for (AnalysisModel.WhyStep step : why.steps()) {
                String where = location(element.uri(), step);
                html.append("<br>").append(indent).append("&nbsp;&nbsp;\u21d0 ")
                        .append(StringUtil.escapeXmlEntities(where))
                        .append(": ").append(StringUtil.escapeXmlEntities(step.text()));
                plain.append(" <= ").append(where).append(": ").append(step.text());
            }
        }
    }

    static String location(String elementUri, AnalysisModel.WhyStep step) {
        if (step.uri() == null || step.uri().equals(elementUri)) return "line " + step.line();
        String file = step.uri().substring(step.uri().lastIndexOf('/') + 1);
        return file + ":" + step.line();
    }

    /**
     * The parameters of a method element that have annotations to show, in declaration order. Language-neutral: a
     * parameter's fqn is its method's, then {@code :index:name}.
     */
    static List<AnalysisModel.ElementAnnotation> parameters(List<AnalysisModel.ElementAnnotation> annotations,
                                                           AnalysisModel.ElementAnnotation method) {
        if (!"METHOD".equals(method.kind()) || method.fqn() == null) return List.of();
        String prefix = method.fqn() + ":";
        return annotations.stream()
                .filter(a -> "PARAMETER".equals(a.kind()) && a.fqn() != null && a.fqn().startsWith(prefix)
                             && a.fqn().indexOf(':', prefix.length()) > prefix.length()
                             && !a.displayAnnotations().isEmpty())
                .sorted(java.util.Comparator.comparingInt(a -> index(a.fqn(), prefix.length())))
                .toList();
    }

    private static int index(String parameterFqn, int from) {
        try {
            return Integer.parseInt(parameterFqn.substring(from, parameterFqn.indexOf(':', from)));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * The maddi kinds, in order of preference, of the declaration whose NAME is this leaf; empty for none. Java's;
     * a language with another PSI overrides it (the Kotlin provider). A marker must anchor on a leaf, which the
     * name identifier is.
     */
    protected List<String> kindsOf(PsiElement leaf) {
        if (!(leaf instanceof PsiIdentifier)) return List.of();
        PsiElement parent = leaf.getParent();
        // a type parameter is a PsiClass too: its name would get its class's verdict (maddi-dist#6)
        if (parent instanceof PsiClass && !(parent instanceof PsiTypeParameter)) return List.of("TYPE");
        if (parent instanceof PsiMethod) return List.of("METHOD");
        if (parent instanceof PsiField) return List.of("FIELD");
        return List.of();
    }
}
