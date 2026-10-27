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

package io.codelaser.maddi.ide.eclipse.tests;

import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.ide.eclipse.MaddiEclipseConfigBuilder;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live (headless) check that {@link MaddiEclipseConfigBuilder} maps a real JDT project model to an
 * {@code AnalyzeConfig}. Runs inside an Equinox runtime with a real workspace; creates a Java project with a
 * source folder + output dir, then asserts the mapping — the Eclipse analog of the IntelliJ
 * {@code MaddiConfigBuilderTest}. Confirms the plugin's JDT integration works, not just compiles.
 */
public class MaddiEclipseConfigBuilderTest {

    private static final String JDK_HOME = "/opt/jdk-25";

    @Test
    @DisplayName("a JDT source folder and its output dir map onto an AnalyzeConfig")
    public void sourceRootAndOutputDirBecomeConfig() throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("MaddiSample");
        if (!project.exists()) project.create(null);
        project.open(null);
        IProjectDescription description = project.getDescription();
        description.setNatureIds(new String[]{JavaCore.NATURE_ID});
        project.setDescription(description, null);

        IJavaProject javaProject = JavaCore.create(project);
        IFolder src = project.getFolder("src");
        if (!src.exists()) src.create(true, true, null);
        IFolder bin = project.getFolder("bin");
        if (!bin.exists()) bin.create(true, true, null);
        javaProject.setRawClasspath(new IClasspathEntry[]{JavaCore.newSourceEntry(src.getFullPath())}, null);
        javaProject.setOutputLocation(bin.getFullPath(), null);

        AnalysisModel.AnalyzeConfig config = new MaddiEclipseConfigBuilder().build(javaProject, JDK_HOME, false);

        assertEquals(JDK_HOME, config.sdkHome(), "the maddi JDK is the analysis SDK");
        assertEquals("UTF-8", config.sourceEncoding());
        assertTrue(config.jmods().contains("java.base"), "java.base is always loaded");
        assertTrue(config.parallel(), "whole-project analysis runs in parallel");

        String srcAbs = src.getLocation().toOSString();
        assertTrue(config.sources().stream().anyMatch(s -> s.path().equals(srcAbs) && !s.test()),
                "the source folder maps to a main (non-test) source root");

        String binAbs = bin.getLocation().toOSString();
        assertTrue(config.classpath().stream()
                        .anyMatch(c -> c.path().equals(binAbs) && "compile".equals(c.scope())),
                "the compiler output dir (hot class files) is a compile-scope classpath entry");
    }
}
