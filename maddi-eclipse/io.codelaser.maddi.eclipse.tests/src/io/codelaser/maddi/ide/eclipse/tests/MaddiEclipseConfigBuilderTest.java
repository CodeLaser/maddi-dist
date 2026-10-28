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
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live (headless) check that {@link MaddiEclipseConfigBuilder} maps a real JDT project model to an
 * {@code AnalyzeConfig} of per-project source sets. Runs inside an Equinox runtime with a real workspace — the
 * Eclipse analog of the IntelliJ {@code MaddiConfigBuilderTest}. Confirms the plugin's JDT integration works, not
 * just compiles.
 */
public class MaddiEclipseConfigBuilderTest {

    private static final String JDK_HOME = "/opt/jdk-25";
    private static final IClasspathAttribute[] TEST = {JavaCore.newClasspathAttribute(IClasspathAttribute.TEST, "true")};

    @Test
    @DisplayName("a JDT source folder and its output folder become a main source set")
    public void sourceFolderBecomesMainSet() throws Exception {
        IJavaProject javaProject = javaProject("MaddiSample");
        IFolder src = folder(javaProject, "src");
        IFolder bin = folder(javaProject, "bin");
        javaProject.setRawClasspath(new IClasspathEntry[]{JavaCore.newSourceEntry(src.getFullPath())}, null);
        javaProject.setOutputLocation(bin.getFullPath(), null);

        AnalysisModel.AnalyzeConfig config = new MaddiEclipseConfigBuilder().build(javaProject, JDK_HOME, false);

        assertEquals(JDK_HOME, config.sdkHome(), "the maddi JDK is the analysis SDK");
        assertEquals("UTF-8", config.sourceEncoding());
        assertTrue(config.jmods().contains("java.base"), "java.base is always loaded");
        assertTrue(config.parallel(), "whole-project analysis runs in parallel");
        assertTrue(config.sources().isEmpty() && config.classpath().isEmpty(), "the flat form stays empty");

        assertEquals(1, config.moduleSourceSets().size());
        AnalysisModel.ModuleSourceSet main = config.moduleSourceSets().getFirst();
        assertEquals("MaddiSample/main", main.name());
        assertFalse(main.test());
        assertEquals(List.of(src.getLocation().toOSString()), main.sourceDirectories());
        assertEquals(bin.getLocation().toOSString(), main.outputPath(), "the output folder is the set's identity");
        assertFalse(main.classPath().contains(bin.getLocation().toOSString()), "never its own class path");
    }

    @Test
    @DisplayName("test folders form a test set on the main set; a test-only library stays off the main class path")
    public void testFolderBecomesTestSet() throws Exception {
        IJavaProject javaProject = javaProject("MaddiWithTests");
        IFolder src = folder(javaProject, "src");
        IFolder test = folder(javaProject, "test");
        IFolder testBin = folder(javaProject, "bin-test");
        IFile lib = file(javaProject, "lib.jar");
        IFile testLib = file(javaProject, "test-lib.jar");
        javaProject.setRawClasspath(new IClasspathEntry[]{
                JavaCore.newSourceEntry(src.getFullPath()),
                JavaCore.newSourceEntry(test.getFullPath(), null, null, testBin.getFullPath(), TEST),
                JavaCore.newLibraryEntry(lib.getFullPath(), null, null),
                JavaCore.newLibraryEntry(testLib.getFullPath(), null, null, null, TEST, false)}, null);

        List<AnalysisModel.ModuleSourceSet> sets =
                new MaddiEclipseConfigBuilder().build(javaProject, JDK_HOME, false).moduleSourceSets();

        assertEquals(List.of("MaddiWithTests/main", "MaddiWithTests/test"),
                sets.stream().map(AnalysisModel.ModuleSourceSet::name).toList());
        AnalysisModel.ModuleSourceSet main = sets.get(0);
        AnalysisModel.ModuleSourceSet tests = sets.get(1);
        assertTrue(tests.test());
        assertEquals(List.of("MaddiWithTests/main"), tests.dependencies(), "tests compile against their main set");
        assertEquals(testBin.getLocation().toOSString(), tests.outputPath());
        String libPath = lib.getLocation().toOSString();
        String testLibPath = testLib.getLocation().toOSString();
        assertEquals(List.of(libPath), main.classPath());
        assertEquals(List.of(libPath, testLibPath), tests.classPath());
    }

    @Test
    @DisplayName("a required project is analysed too, named as a dependency, and its output is no class-path entry")
    public void requiredProjectBecomesDependency() throws Exception {
        IJavaProject base = javaProject("MaddiBase");
        IFolder baseSrc = folder(base, "src");
        IFolder baseBin = folder(base, "bin");
        base.setRawClasspath(new IClasspathEntry[]{JavaCore.newSourceEntry(baseSrc.getFullPath())}, null);
        base.setOutputLocation(baseBin.getFullPath(), null);
        IJavaProject app = javaProject("MaddiApp");
        IFolder appSrc = folder(app, "src");
        app.setRawClasspath(new IClasspathEntry[]{
                JavaCore.newSourceEntry(appSrc.getFullPath()),
                JavaCore.newProjectEntry(base.getPath())}, null);

        List<AnalysisModel.ModuleSourceSet> sets =
                new MaddiEclipseConfigBuilder().build(app, JDK_HOME, false).moduleSourceSets();

        assertEquals(List.of("MaddiApp/main", "MaddiBase/main"),
                sets.stream().map(AnalysisModel.ModuleSourceSet::name).toList());
        assertEquals(List.of("MaddiBase/main"), sets.getFirst().dependencies());
        assertFalse(sets.getFirst().classPath().contains(baseBin.getLocation().toOSString()),
                "the required project is source, not hot class files");
    }

    @Test
    @DisplayName("the compliance level is the API release only when JDT compiles with --release")
    public void releaseOnlyWithReleaseOption() throws Exception {
        IJavaProject javaProject = javaProject("MaddiRelease");
        javaProject.setOption(JavaCore.COMPILER_COMPLIANCE, "17");
        javaProject.setOption(JavaCore.COMPILER_RELEASE, JavaCore.DISABLED);
        assertEquals(0, MaddiEclipseConfigBuilder.apiRelease(javaProject), "-source 17 says nothing of the API");
        javaProject.setOption(JavaCore.COMPILER_RELEASE, JavaCore.ENABLED);
        assertEquals(17, MaddiEclipseConfigBuilder.apiRelease(javaProject));
        javaProject.setOption(JavaCore.COMPILER_COMPLIANCE, "1.8");
        assertEquals(8, MaddiEclipseConfigBuilder.apiRelease(javaProject));
    }

    static IJavaProject javaProject(String name) throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (!project.exists()) project.create(null);
        project.open(null);
        IProjectDescription description = project.getDescription();
        description.setNatureIds(new String[]{JavaCore.NATURE_ID});
        project.setDescription(description, null);
        return JavaCore.create(project);
    }

    static IFolder folder(IJavaProject javaProject, String name) throws Exception {
        IFolder folder = javaProject.getProject().getFolder(name);
        if (!folder.exists()) folder.create(true, true, null);
        return folder;
    }

    static IFile file(IJavaProject javaProject, String name) throws Exception {
        IFile file = javaProject.getProject().getFile(name);
        if (!file.exists()) file.create(new ByteArrayInputStream(new byte[0]), true, null);
        return file;
    }
}
