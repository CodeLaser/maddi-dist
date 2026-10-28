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

import io.codelaser.maddi.ide.client.AnalysisModel;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the daemon's {@code AnalyzeConfig} from JDT's project model, as PER-PROJECT source sets — the Eclipse
 * analog of the IntelliJ {@code MaddiConfigBuilder}, which explains at length why a flat class-path union is
 * wrong (too many class-path pairs, lost {@code provided} scope, every type reachable as source AND bytecode).
 * <p>
 * What is analysed: the selected project and every workspace Java project it requires, transitively — the
 * Eclipse counterpart of an IntelliJ project with its modules (m2e and Buildship import one Eclipse project per
 * module). Each project gives a main set and, when it has test source folders, a test set. A set carries the
 * project's own libraries and names the main sets of the projects it requires; the daemon closes over those
 * itself. A project's output folder is reported as its set's {@code outputPath}, never as a class-path entry of
 * another set. The JDK does NOT come across: maddi loads {@code jmods} from its own configured JDK 25+, so the JRE
 * container is skipped.
 */
public final class MaddiEclipseConfigBuilder {

    /** JDK modules loaded by default; mirrors the IntelliJ builder. */
    private static final List<String> DEFAULT_JMODS = List.of(
            "java.base", "java.logging", "java.xml", "java.sql", "java.naming",
            "java.desktop", "java.management", "java.net.http", "java.compiler");

    private static final String JRE_CONTAINER = "org.eclipse.jdt.launching.JRE_CONTAINER";

    /**
     * @param javaProject    the JDT project to analyze, with the projects it requires
     * @param jdkHome        home of the maddi JDK (25+); the analysis SDK AND the daemon's run JDK — NOT the
     *                       project's JRE, which may target an older release maddi cannot read java.base from
     * @param warnNearMisses ask the analyzer for advisory near-miss warnings (opt-in; noisy by nature)
     */
    public AnalysisModel.AnalyzeConfig build(IJavaProject javaProject, String jdkHome, boolean warnNearMisses)
            throws CoreException {
        List<AnalysisModel.ModuleSourceSet> sets = new ArrayList<>();
        for (IJavaProject project : withRequiredProjects(javaProject)) {
            addSets(project, sets);
        }
        IProject project = javaProject.getProject();
        String workingDirectory = project.getLocation() == null ? null : project.getLocation().toOSString();
        return new AnalysisModel.AnalyzeConfig(
                workingDirectory,
                jdkHome,
                "UTF-8",
                DEFAULT_JMODS,
                List.of(),      // the flat pair stays empty: the sets carry the structure
                List.of(),
                List.of(),
                true,
                warnNearMisses,
                sets);
    }

    /** The project followed by the open Java projects it requires, transitively, each once. */
    public static List<IJavaProject> withRequiredProjects(IJavaProject start) throws CoreException {
        Set<String> seen = new LinkedHashSet<>();
        List<IJavaProject> result = new ArrayList<>();
        List<IJavaProject> todo = new ArrayList<>(List.of(start));
        while (!todo.isEmpty()) {
            IJavaProject project = todo.removeFirst();
            if (!seen.add(project.getElementName())) continue;
            result.add(project);
            for (String name : requiredProjectNames(project)) {
                IJavaProject required = javaProject(name);
                if (required != null) todo.add(required);
            }
        }
        return result;
    }

    private static void addSets(IJavaProject project, List<AnalysisModel.ModuleSourceSet> sets)
            throws CoreException {
        List<String> main = new ArrayList<>();
        List<String> test = new ArrayList<>();
        String defaultOutput = absolute(project.getOutputLocation());
        String mainOutput = null;
        String testOutput = null;
        // path -> whether ONLY tests use it; a library on both class paths counts as main
        Map<String, Boolean> libraries = new LinkedHashMap<>();
        for (IClasspathEntry entry : project.getRawClasspath()) {
            switch (entry.getEntryKind()) {
                case IClasspathEntry.CPE_SOURCE -> {
                    String src = absolute(entry.getPath());
                    if (src == null) continue;
                    // a source folder's own output overrides the project default (e.g. test sources → bin/test)
                    String out = entry.getOutputLocation() != null ? absolute(entry.getOutputLocation())
                            : defaultOutput;
                    if (entry.isTest()) {
                        test.add(src);
                        if (testOutput == null) testOutput = out;
                    } else {
                        main.add(src);
                        if (mainOutput == null) mainOutput = out;
                    }
                }
                case IClasspathEntry.CPE_LIBRARY -> addLibrary(libraries, entry, entry.isTest());
                case IClasspathEntry.CPE_VARIABLE -> {
                    IClasspathEntry resolved = JavaCore.getResolvedClasspathEntry(entry);
                    if (resolved != null && resolved.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
                        addLibrary(libraries, resolved, entry.isTest());
                    }
                }
                case IClasspathEntry.CPE_CONTAINER -> {
                    if (JRE_CONTAINER.equals(entry.getPath().segment(0))) continue; // JDK comes from jmods
                    IClasspathContainer container = JavaCore.getClasspathContainer(entry.getPath(), project);
                    if (container == null) continue;
                    for (IClasspathEntry resolved : container.getClasspathEntries()) {
                        if (resolved.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
                            addLibrary(libraries, resolved, entry.isTest() || resolved.isTest());
                        }
                    }
                }
                default -> {
                    // required projects become dependencies below
                }
            }
        }
        if (main.isEmpty() && test.isEmpty()) return; // no Java sources of its own

        List<String> mainClassPath = new ArrayList<>();
        List<String> testClassPath = new ArrayList<>();
        libraries.forEach((path, testOnly) -> {
            if (!testOnly) mainClassPath.add(path);
            testClassPath.add(path);
        });
        List<String> dependencies = new ArrayList<>();
        for (String name : requiredProjectNames(project)) {
            if (javaProject(name) != null) dependencies.add(mainSetName(name));
        }
        int release = apiRelease(project);
        String name = project.getElementName();
        if (!main.isEmpty()) {
            sets.add(new AnalysisModel.ModuleSourceSet(mainSetName(name), main, mainOutput, false, release,
                    dependencies, mainClassPath));
        }
        if (!test.isEmpty()) {
            List<String> testDependencies = new ArrayList<>(dependencies);
            if (!main.isEmpty()) testDependencies.add(mainSetName(name));
            sets.add(new AnalysisModel.ModuleSourceSet(testSetName(name), test, testOutput, true, release,
                    testDependencies, testClassPath));
        }
    }

    private static void addLibrary(Map<String, Boolean> libraries, IClasspathEntry entry, boolean testOnly) {
        String path = absolute(entry.getPath());
        if (path != null) libraries.merge(path, testOnly, (a, b) -> a && b);
    }

    /**
     * The names of the projects this one requires: its own project entries, and those a container resolves to
     * (m2e's "Maven Dependencies" turns a workspace module into a project entry inside the container).
     */
    private static List<String> requiredProjectNames(IJavaProject project) throws CoreException {
        List<String> names = new ArrayList<>();
        for (IClasspathEntry entry : project.getRawClasspath()) {
            if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
                names.add(entry.getPath().lastSegment());
            } else if (entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER
                       && !JRE_CONTAINER.equals(entry.getPath().segment(0))) {
                IClasspathContainer container = JavaCore.getClasspathContainer(entry.getPath(), project);
                if (container == null) continue;
                for (IClasspathEntry resolved : container.getClasspathEntries()) {
                    if (resolved.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
                        names.add(resolved.getPath().lastSegment());
                    }
                }
            }
        }
        return names;
    }

    private static IJavaProject javaProject(String name) {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (!project.exists() || !project.isOpen()) return null;
        try {
            if (!project.hasNature(JavaCore.NATURE_ID)) return null;
        } catch (CoreException e) {
            return null;
        }
        return JavaCore.create(project);
    }

    static String mainSetName(String project) {
        return project + "/main";
    }

    static String testSetName(String project) {
        return project + "/test";
    }

    /**
     * The release whose API the project compiles against, or 0 for "not known", which the daemon reads as "the
     * JDK maddi runs on".
     * <p>
     * Unlike IntelliJ, whose model holds only the language level ({@code -source}; see the IntelliJ builder for
     * what reporting that as a release broke), JDT knows whether it compiles with {@code --release}: the
     * {@code compiler.release} option, which m2e sets from {@code maven.compiler.release}. Only then is the
     * compliance level an API release. Without it the project compiles against the JRE it is bound to, which
     * maddi does not use, so 0 is the honest answer.
     */
    public static int apiRelease(IJavaProject project) {
        if (!JavaCore.ENABLED.equals(project.getOption(JavaCore.COMPILER_RELEASE, true))) return 0;
        String compliance = project.getOption(JavaCore.COMPILER_COMPLIANCE, true);
        if (compliance == null) return 0;
        String version = compliance.startsWith("1.") ? compliance.substring(2) : compliance;
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * A JDT {@link IPath} is workspace-relative for resources inside the workspace (e.g. {@code /Proj/bin})
     * and absolute for external artifacts (an out-of-workspace jar). Resolve to a real filesystem path.
     */
    private static String absolute(IPath path) {
        if (path == null) return null;
        IResource res = ResourcesPlugin.getWorkspace().getRoot().findMember(path);
        if (res != null && res.getLocation() != null) return res.getLocation().toOSString();
        return path.toOSString(); // already filesystem-absolute (external jar / library)
    }
}
