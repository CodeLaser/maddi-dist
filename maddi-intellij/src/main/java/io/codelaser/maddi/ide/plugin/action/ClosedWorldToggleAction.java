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

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Whether this project is an application (road-to-immutability §055, "Transfer"): no code outside it calls its public
 * methods, so the ownership pass may count a parameter every caller hands over as owned. Per project, off by default
 * (a library's public methods have callers maddi cannot see); read at the next analysis.
 */
public class ClosedWorldToggleAction extends DumbAwareToggleAction {

    private static final String KEY = "maddi.closedWorld";

    public static boolean isClosedWorld(@NotNull Project project) {
        return PropertiesComponent.getInstance(project).getBoolean(KEY, false);
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        return project != null && isClosedWorld(project);
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        Project project = e.getProject();
        if (project != null) PropertiesComponent.getInstance(project).setValue(KEY, state, false);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        super.update(e);
        e.getPresentation().setEnabled(e.getProject() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
