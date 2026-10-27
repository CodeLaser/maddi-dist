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
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.project.Project;
import io.codelaser.maddi.ide.plugin.analysis.MaddiAnalysisService;
import org.jetbrains.annotations.NotNull;

/**
 * Manual trigger: compile the project first (so maddi sees fresh, hot {@code .class} files on the
 * classpath) and, on success, run a whole-project analysis.
 */
public class AnalyzeWithMaddiAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        CompilerManager.getInstance(project).make((aborted, errors, warnings, context) -> {
            // even with compile errors we still analyze: the daemon returns findings-only for partial projects
            if (!aborted) {
                MaddiAnalysisService.getInstance(project).analyzeInBackground();
            }
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
