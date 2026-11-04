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
package io.codelaser.maddi.ide.plugin.kotlin;

import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtEnumEntry;
import org.jetbrains.kotlin.psi.KtNamedDeclaration;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtParameter;
import org.jetbrains.kotlin.psi.KtProperty;
import org.jetbrains.kotlin.psi.KtTypeReference;

import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;

/**
 * Kotlin's PSI mapped onto maddi's element kinds, for the surfaces that anchor on a declaration's name.
 * <p>
 * The anchor is the name token itself: Kotlin has no {@code PsiIdentifier}, its declarations own an
 * {@code IDENTIFIER} leaf ({@code getNameIdentifier()}). Matching the leaf to an analysed element is then the
 * shared range-containment rule, which is why the kind is all this class decides.
 * <p>
 * ⚠ Only the shapes whose meaning is plain are mapped. A declaration with no name of its own (a primary
 * constructor, {@code companion object}, an {@code init} block) has no leaf to anchor on and gets nothing yet;
 * a constructor {@code val} parameter shows its PARAMETER verdict inline and its FIELD verdict in the gutter. An
 * extension function's receiver, which has no name, is anchored on its type.
 */
public final class KotlinDeclarations {
    private KotlinDeclarations() {
    }

    /**
     * The kinds to look for at this leaf, in order of preference. A property is its backing FIELD, and when it
     * has none (a custom getter, {@code val sum get() = x + y}) the analysed element is its accessor, a METHOD
     * over the property's range: without the fallback that verdict had nowhere to go.
     */
    public static List<String> kindsOf(PsiElement leaf) {
        String kind = kindOf(leaf);
        if (kind == null) return List.of();
        return "FIELD".equals(kind) ? List.of("FIELD", "METHOD") : List.of(kind);
    }

    /**
     * The kinds the GUTTER looks for, which skips parameters. A constructor {@code val}/{@code var} parameter is also
     * a field, over the same range, and the two verdicts can differ: the inline hint shows the parameter's, and the
     * gutter the field's, so both are visible where each kind is normally shown.
     */
    public static List<String> gutterKindsOf(PsiElement leaf) {
        if (leaf.getParent() instanceof KtParameter parameter && parameter.hasValOrVar()
            && parameter.getNameIdentifier() == leaf) {
            return List.of("FIELD");
        }
        return kindsOf(leaf);
    }

    public static @Nullable String kindOf(PsiElement leaf) {
        IElementType type = leaf.getNode() == null ? null : leaf.getNode().getElementType();
        if (type != KtTokens.IDENTIFIER) return null;
        if (isReceiverAnchor(leaf)) return "PARAMETER";
        if (!(leaf.getParent() instanceof KtNamedDeclaration declaration)
            || declaration.getNameIdentifier() != leaf) {
            return null;
        }
        // an enum entry is a KtClass, but maddi analyses it as a field of its enum: as a TYPE it showed the enum's
        // verdict on every constant (maddi-dist#6)
        if (declaration instanceof KtEnumEntry) return "FIELD";
        if (declaration instanceof KtClassOrObject) return "TYPE";
        if (declaration instanceof KtNamedFunction) return "METHOD";
        if (declaration instanceof KtProperty) return "FIELD";   // no backing field: see kindsOf
        if (declaration instanceof KtParameter) return "PARAMETER";
        return null;
    }

    /**
     * An extension function's receiver has no name to anchor on; the daemon positions it at its type reference
     * ({@code StringBuilder} in {@code fun StringBuilder.shout()}). The anchor is that reference's FIRST identifier,
     * so {@code List<String>} gets one hint, not one per name inside it.
     */
    private static boolean isReceiverAnchor(PsiElement leaf) {
        // the outermost type reference around the leaf, up to the nearest function
        KtTypeReference reference = null;
        PsiElement up = leaf.getParent();
        for (; up != null && !(up instanceof KtNamedFunction); up = up.getParent()) {
            if (up instanceof KtTypeReference r) reference = r;
        }
        if (reference == null || !(up instanceof KtNamedFunction function)
            || function.getReceiverTypeReference() != reference) {
            return false;
        }
        for (PsiElement l = PsiTreeUtil.firstChild(reference); l != null; l = PsiTreeUtil.nextLeaf(l)) {
            if (l.getNode() != null && l.getNode().getElementType() == KtTokens.IDENTIFIER) return l == leaf;
        }
        return false;
    }
}
