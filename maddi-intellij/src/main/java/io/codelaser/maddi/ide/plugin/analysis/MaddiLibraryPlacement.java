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

import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiEllipsisType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import io.codelaser.maddi.ide.client.AnalysisModel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A library file's declarations (Set.java from the JDK's sources, a library's sources jar) given the values the last
 * analysis has for them. A library has no position in the analysis, so its elements are matched by declaring type,
 * kind and signature, and placed on the name of the declaration they matched: from there on the editor's surfaces
 * treat them as they treat the project's.
 */
public final class MaddiLibraryPlacement {
    private MaddiLibraryPlacement() {
    }

    /** The top-level and nested types of the file, by qualified name: what to ask the daemon for. */
    public static List<String> typesOf(PsiJavaFile file) {
        List<String> out = new ArrayList<>();
        for (PsiClass c : file.getClasses()) {
            if (c.getQualifiedName() != null) out.add(c.getQualifiedName());
        }
        return out;
    }

    public static List<AnalysisModel.ElementAnnotation> place(PsiJavaFile file, Document doc, String uri,
                                                              List<AnalysisModel.LibraryElement> elements) {
        Map<String, AnalysisModel.LibraryElement> byKey = new HashMap<>();
        for (AnalysisModel.LibraryElement e : elements) byKey.put(key(e.type(), e.kind(), e.signature()), e);
        List<AnalysisModel.ElementAnnotation> out = new ArrayList<>();
        for (PsiClass c : PsiTreeUtil.findChildrenOfType(file, PsiClass.class)) {
            // a type parameter is a PsiClass too; an anonymous or local class has no qualified name
            if (c instanceof PsiTypeParameter || c.getQualifiedName() == null) continue;
            String type = c.getQualifiedName();
            add(out, doc, uri, byKey.get(key(type, "TYPE", type)), c.getNameIdentifier());
            for (PsiField field : c.getFields()) {
                add(out, doc, uri, byKey.get(key(type, "FIELD", field.getName())), field.getNameIdentifier());
            }
            for (PsiMethod method : c.getMethods()) {
                String signature = signature(method);
                add(out, doc, uri, byKey.get(key(type, "METHOD", signature)), method.getNameIdentifier());
                PsiParameter[] parameters = method.getParameterList().getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    add(out, doc, uri, byKey.get(key(type, "PARAMETER", signature + "#" + i)),
                            parameters[i].getNameIdentifier());
                }
            }
        }
        return out;
    }

    /**
     * As the daemon writes it: the name ({@code <init>} for a constructor) and the parameters' erasures, a varargs
     * parameter as the array it is.
     */
    static String signature(PsiMethod method) {
        StringBuilder sb = new StringBuilder(method.isConstructor() ? "<init>" : method.getName()).append('(');
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) sb.append(',');
            PsiType type = parameters[i].getType();
            if (type instanceof PsiEllipsisType ellipsis) type = ellipsis.toArrayType();
            sb.append(TypeConversionUtil.erasure(type).getCanonicalText());
        }
        return sb.append(')').toString();
    }

    private static String key(String type, String kind, String signature) {
        return type + "|" + kind + "|" + signature;
    }

    private static void add(List<AnalysisModel.ElementAnnotation> out, Document doc, String uri,
                            @Nullable AnalysisModel.LibraryElement element, @Nullable PsiIdentifier name) {
        if (element == null || name == null) return;
        int start = name.getTextRange().getStartOffset();
        int last = Math.max(start, name.getTextRange().getEndOffset() - 1);
        if (last >= doc.getTextLength()) return;
        int beginLine = doc.getLineNumber(start);
        int endLine = doc.getLineNumber(last);
        // 1-based, the end column inclusive, as the daemon's positions (MaddiPositions)
        out.add(AnalysisModel.place(element, uri, beginLine + 1, start - doc.getLineStartOffset(beginLine) + 1,
                endLine + 1, last - doc.getLineStartOffset(endLine) + 1));
    }
}
