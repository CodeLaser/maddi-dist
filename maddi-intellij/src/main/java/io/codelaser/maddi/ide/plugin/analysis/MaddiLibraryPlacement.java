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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

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
        declarations(file, (key, name) -> {
            AnalysisModel.LibraryElement element = byKey.get(key);
            int[] at = element == null ? null : position(doc, name);
            if (at != null) out.add(AnalysisModel.place(element, uri, at[0], at[1], at[2], at[3]));
        });
        return out;
    }

    /**
     * A project file's annotations on its declarations as they are NOW. The positions are those of the source the
     * analysis read, and stop pointing at the declaration as soon as an edit moves its line: splitting a class's
     * {@code {} onto a line of its own took the field's hints away. So an element that carries its name (owner and
     * signature, from a daemon of 0.9.6 on) is placed on the declaration of that name, wherever it is now; one whose
     * declaration is gone (renamed, deleted) is dropped rather than shown on whatever took its place. What has no
     * such name (an older daemon, a member of an anonymous or local class) keeps its position.
     */
    public static List<AnalysisModel.ElementAnnotation> follow(PsiJavaFile file, Document doc,
                                                               List<AnalysisModel.ElementAnnotation> annotations) {
        Map<String, AnalysisModel.ElementAnnotation> byKey = new HashMap<>();
        List<AnalysisModel.ElementAnnotation> out = new ArrayList<>();
        java.util.Set<String> types = new java.util.HashSet<>();
        declarations(file, (key, name) -> types.add(key.substring(0, key.indexOf('|'))));
        for (AnalysisModel.ElementAnnotation a : annotations) {
            if (a.owner() != null && a.signature() != null && types.contains(a.owner())) {
                byKey.put(key(a.owner(), a.kind(), a.signature()), a);
            } else {
                out.add(a);
            }
        }
        if (byKey.isEmpty()) return annotations;
        declarations(file, (key, name) -> {
            AnalysisModel.ElementAnnotation a = byKey.get(key);
            int[] at = a == null ? null : position(doc, name);
            if (at != null) out.add(a.at(at[0], at[1], at[2], at[3]));
        });
        return out;
    }

    /** Every named declaration of the file, by its key: of a type, its fields, methods and their parameters. */
    private static void declarations(PsiJavaFile file, java.util.function.BiConsumer<String, PsiIdentifier> sink) {
        for (PsiClass c : PsiTreeUtil.findChildrenOfType(file, PsiClass.class)) {
            // a type parameter is a PsiClass too; an anonymous or local class has no qualified name
            if (c instanceof PsiTypeParameter || c.getQualifiedName() == null) continue;
            String type = c.getQualifiedName();
            accept(sink, key(type, "TYPE", type), c.getNameIdentifier());
            for (PsiField field : c.getFields()) {
                accept(sink, key(type, "FIELD", field.getName()), field.getNameIdentifier());
            }
            for (PsiMethod method : c.getMethods()) {
                String signature = signature(method);
                accept(sink, key(type, "METHOD", signature), method.getNameIdentifier());
                PsiParameter[] parameters = method.getParameterList().getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    accept(sink, key(type, "PARAMETER", signature + "#" + i), parameters[i].getNameIdentifier());
                }
            }
        }
    }

    private static void accept(java.util.function.BiConsumer<String, PsiIdentifier> sink, String key,
                               @Nullable PsiIdentifier name) {
        if (name != null) sink.accept(key, name);
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

    /** The name's range as the daemon writes positions (MaddiPositions): 1-based, the end column inclusive. */
    private static int @Nullable [] position(Document doc, PsiIdentifier name) {
        int start = name.getTextRange().getStartOffset();
        int last = Math.max(start, name.getTextRange().getEndOffset() - 1);
        if (last >= doc.getTextLength()) return null;
        int beginLine = doc.getLineNumber(start);
        int endLine = doc.getLineNumber(last);
        return new int[]{beginLine + 1, start - doc.getLineStartOffset(beginLine) + 1,
                endLine + 1, last - doc.getLineStartOffset(endLine) + 1};
    }
}
