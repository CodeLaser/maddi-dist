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

package io.codelaser.maddi.ide.plugin.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import io.codelaser.maddi.ide.plugin.analysis.MaddiTranslation;
import org.jetbrains.annotations.NotNull;

/**
 * Translate to Kotlin with maddi: a module (selected in the Project view) as one Kotlin code base, or Java files as a
 * preview beside their Java. See {@link MaddiTranslation}.
 */
public class TranslateToKotlinAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        MaddiTranslation.Selection selection = MaddiTranslation.of(project, e.getData(LangDataKeys.MODULE_CONTEXT),
                e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY));
        if (selection != null) MaddiTranslation.start(project, selection);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Module module = e.getData(LangDataKeys.MODULE_CONTEXT);
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        boolean javaFile = files != null && java.util.Arrays.stream(files)
                .anyMatch(f -> !f.isDirectory() && "java".equals(f.getExtension()));
        e.getPresentation().setEnabledAndVisible(e.getProject() != null && (module != null || javaFile));
        e.getPresentation().setText(module != null ? "Translate Module to Kotlin with maddi"
                : "Translate to Kotlin with maddi (Preview)");
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
