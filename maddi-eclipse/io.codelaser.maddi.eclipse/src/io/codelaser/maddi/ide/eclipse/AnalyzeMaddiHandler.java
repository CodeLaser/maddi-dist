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

package io.codelaser.maddi.ide.eclipse;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * "Analyze with maddi", from a project's context menu or the Project menu: hands the project of the selection,
 * else that of the active editor, to {@link MaddiAnalysis}, which builds it, runs the analysis on the shared daemon
 * off the UI thread and surfaces the result.
 */
public class AnalyzeMaddiHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        IProject project = projectOf(HandlerUtil.getCurrentSelection(event));
        if (project == null) project = projectOf(HandlerUtil.getActiveEditor(event));
        if (project == null) {
            MaddiAnalysis.reportNoProject();
            return null;
        }
        IJavaProject javaProject = JavaCore.create(project);
        if (javaProject == null || !javaProject.exists()) {
            MaddiAnalysis.reportNoProject();
            return null;
        }
        MaddiAnalysis.schedule(javaProject, true);
        return null;
    }

    private static IProject projectOf(IEditorPart editor) {
        if (editor == null) return null;
        IFile file = editor.getEditorInput().getAdapter(IFile.class);
        return file == null ? null : file.getProject();
    }

    private static IProject projectOf(ISelection selection) {
        if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty()) return null;
        Object element = structured.getFirstElement();
        if (element instanceof IProject project) return project;
        if (element instanceof IJavaProject javaProject) return javaProject.getProject();
        if (element instanceof IAdaptable adaptable) {
            IProject adapted = adaptable.getAdapter(IProject.class);
            if (adapted != null) return adapted;
            IJavaProject javaProject = adaptable.getAdapter(IJavaProject.class);
            if (javaProject != null) return javaProject.getProject();
        }
        return null;
    }
}
