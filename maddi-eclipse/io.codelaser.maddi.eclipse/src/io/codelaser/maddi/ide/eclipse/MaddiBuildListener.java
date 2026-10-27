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

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Re-analyzes a Java project after Eclipse builds it, when the auto-analyze preference is on. Gated so it
 * costs nothing when off; the analysis itself is coalesced ({@link MaddiAnalysis}). Registered
 * programmatically by {@link MaddiEclipsePlugin} for {@code POST_BUILD} events.
 */
public class MaddiBuildListener implements IResourceChangeListener {

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        if (event.getType() != IResourceChangeEvent.POST_BUILD) return;
        if (!MaddiPreferences.autoAnalyzeOnBuild()) return;
        IResourceDelta delta = event.getDelta();
        if (delta == null) return;

        Set<IProject> built = new LinkedHashSet<>();
        for (IResourceDelta child : delta.getAffectedChildren()) {
            IResource resource = child.getResource();
            if (resource instanceof IProject project && project.isOpen() && isJava(project)) {
                built.add(project);
            }
        }
        for (IProject project : built) {
            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject != null && javaProject.exists()) {
                MaddiAnalysis.schedule(javaProject, false);
            }
        }
    }

    private static boolean isJava(IProject project) {
        try {
            return project.hasNature(JavaCore.NATURE_ID);
        } catch (CoreException e) {
            return false;
        }
    }
}
