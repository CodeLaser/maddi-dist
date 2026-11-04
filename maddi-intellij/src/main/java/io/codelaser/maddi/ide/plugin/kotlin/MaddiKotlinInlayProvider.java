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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtParameter;

import com.intellij.psi.PsiElement;

import io.codelaser.maddi.ide.plugin.ui.MaddiInlayProvider;

/** {@link MaddiInlayProvider} on Kotlin sources; registered only when the Kotlin plugin is (maddi-kotlin.xml). */
public class MaddiKotlinInlayProvider extends MaddiInlayProvider {

    @Override
    protected List<String> kindsOf(@NotNull PsiElement leaf) {
        return KotlinDeclarations.kindsOf(leaf);
    }

    /** Before the parameter, where Kotlin writes its annotations ({@code @Nullable val name: String}). */
    @Override
    protected int parameterHintOffset(@NotNull PsiElement leaf) {
        if (leaf.getParent() instanceof KtParameter parameter && parameter.getNameIdentifier() == leaf) {
            return parameter.getTextRange().getStartOffset();
        }
        return leaf.getTextRange().getStartOffset(); // an extension receiver: before its type
    }
}
