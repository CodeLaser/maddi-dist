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

package io.codelaser.maddi.ide.plugin.analysis;

import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LoggedErrorProcessor;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import io.codelaser.maddi.ide.client.AnalysisModel;
import org.jetbrains.jps.model.java.JavaResourceRootType;
import org.jetbrains.kotlin.config.ResourceKotlinRootType;
import org.jetbrains.kotlin.config.SourceKotlinRootType;
import org.jetbrains.kotlin.config.TestSourceKotlinRootType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives {@link MaddiConfigBuilder} against a real (light) IntelliJ project model and asserts the mapping into
 * the daemon's {@code AnalyzeConfig}.
 * <p>
 * ⭐ The load-bearing behaviour CHANGED, and one assertion here inverted. It used to be
 * <b>compiler output dirs → classpath</b> ("hot class files") plus a project-wide library union, in the flat
 * {@code sources}/{@code classpath} pair. That produced three measured failures on the CodeLaser tree
 * (2026-08-21): a quadratic (set, entry) fan-out that OOMed preload at 12 GB and 24 GB, silently dropped
 * PROVIDED/{@code compileOnly} entries, and made every FQN reachable as source AND bytecode.
 * <p>
 * Now each module yields its own {@link AnalysisModel.ModuleSourceSet}: its own class path, its own named
 * direct dependencies, and its compiler output as the set's {@code outputPath} — its IDENTITY, deliberately
 * NOT a class-path entry. {@code testCompilerOutputIsIdentityNotClasspath} is the old
 * {@code testCompilerOutputBecomesClasspath} with its expectation reversed; that reversal is the point of the
 * change, not an accident, so it is named rather than quietly edited.
 */
public class MaddiConfigBuilderTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String JDK_HOME = "/opt/jdk-25";

    // Tests that add source roots / output dirs / libraries create temp VFS dirs; deleting them at teardown
    // fires VFS events into a bundled IDEA-Ultimate async listener (Vue) that throws during init. That logged
    // error is unrelated to the config builder, so swallow ONLY it and keep every other logged error fatal.
    private AccessToken loggedErrorGuard;

    private static final LoggedErrorProcessor SWALLOW_BUNDLED_ASYNC_LISTENER = new LoggedErrorProcessor() {
        @Override
        public Set<Action> processError(String category, String message, String[] details, Throwable t) {
            return fromVueListener(t) ? Action.NONE : Action.ALL;
        }
    };

    private static boolean fromVueListener(Throwable t) {
        for (Throwable e = t; e != null; e = e.getCause()) {
            if (e.getClass().getName().contains("Vue")) return true;
            for (StackTraceElement s : e.getStackTrace()) {
                if (s.getClassName().contains("Vue")) return true;
            }
        }
        return false;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        loggedErrorGuard = LoggedErrorProcessor.executeWith(SWALLOW_BUNDLED_ASYNC_LISTENER);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            super.tearDown(); // deletes temp roots → fires the bundled listener; guard still installed
        } finally {
            if (loggedErrorGuard != null) loggedErrorGuard.finish();
        }
    }

    private AnalysisModel.AnalyzeConfig build() {
        return ReadAction.compute(() -> new MaddiConfigBuilder().build(getProject(), JDK_HOME, false));
    }

    private Module module() {
        return ModuleManager.getInstance(getProject()).getModules()[0];
    }

    /** The per-module source sets, by name. */
    private static Map<String, AnalysisModel.ModuleSourceSet> sets(AnalysisModel.AnalyzeConfig config) {
        Map<String, AnalysisModel.ModuleSourceSet> map = new LinkedHashMap<>();
        for (AnalysisModel.ModuleSourceSet s : config.moduleSourceSets()) map.put(s.name(), s);
        return map;
    }

    /** Every class-path entry of every source set, which is where a project-wide union would show up. */
    private static List<String> allClassPaths(AnalysisModel.AnalyzeConfig config) {
        List<String> all = new ArrayList<>();
        for (AnalysisModel.ModuleSourceSet s : config.moduleSourceSets()) all.addAll(s.classPath());
        return all;
    }

    private static AnalysisModel.ModuleSourceSet main(AnalysisModel.AnalyzeConfig config) {
        return config.moduleSourceSets().stream().filter(s -> !s.test()).findFirst().orElse(null);
    }

    /** Scalar fields come straight through; the maddi JDK is the analysis SDK, not the project SDK. */
    public void testStructuralFields() {
        AnalysisModel.AnalyzeConfig config = build();
        assertEquals(getProject().getBasePath(), config.workingDirectory());
        assertEquals(JDK_HOME, config.sdkHome());
        assertEquals("UTF-8", config.sourceEncoding());
        assertTrue("java.base must always be loaded", config.jmods().contains("java.base"));
        assertTrue("common enterprise jmods expected", config.jmods().contains("java.sql"));
        assertTrue("whole-project analysis runs in parallel", config.parallel());
        assertTrue("no package restriction for whole-project", config.restrictToPackages().isEmpty());
    }

    /** The module's source root is reported inside a main (non-test) per-module source set. */
    public void testMainSourceRootIsPresent() {
        AnalysisModel.AnalyzeConfig config = build();
        assertFalse("the light module should yield at least one source set", config.moduleSourceSets().isEmpty());
        AnalysisModel.ModuleSourceSet mainSet = main(config);
        assertNotNull("expected a non-test source set", mainSet);
        assertFalse("the main set should carry the module's source root", mainSet.sourceDirectories().isEmpty());
        assertTrue("a main set is named <module>/main, so a reader can match it to a dependency name",
                mainSet.name().endsWith("/main"));
    }

    /** The flat pair is no longer populated: all structure now lives in the per-module sets. */
    public void testFlatPairIsEmpty() {
        AnalysisModel.AnalyzeConfig config = build();
        assertTrue("sources must stay empty, or the daemon falls back to the auto-wiring assembler",
                config.sources().isEmpty());
        assertTrue("classpath must stay empty, or the project-wide union is back",
                config.classpath().isEmpty());
    }

    /**
     * The Kotlin plugin's Gradle import marks {@code src/main/kotlin} with its own root type, not Java's; asking
     * for Java's alone sent a Kotlin project with no source set at all.
     */
    public void testKotlinSourceRootsAreSourceSets() throws Exception {
        VirtualFile mainDir = myFixture.getTempDirFixture().findOrCreateDir("kmain");
        VirtualFile testDir = myFixture.getTempDirFixture().findOrCreateDir("ktest");
        PsiTestUtil.addSourceRoot(module(), mainDir, SourceKotlinRootType.INSTANCE);
        PsiTestUtil.addSourceRoot(module(), testDir, TestSourceKotlinRootType.INSTANCE);

        AnalysisModel.AnalyzeConfig config = build();
        assertTrue("a kotlin-source root belongs in a main set", config.moduleSourceSets().stream()
                .anyMatch(s -> !s.test() && s.sourceDirectories().contains(mainDir.getPath())));
        assertTrue("a kotlin-test root belongs in a test set", config.moduleSourceSets().stream()
                .anyMatch(s -> s.test() && s.sourceDirectories().contains(testDir.getPath())));
    }

    /** Resource roots, Java's or Kotlin's, hold no code and stay out of every set. */
    public void testResourceRootsAreNotSourceDirectories() throws Exception {
        VirtualFile javaRes = myFixture.getTempDirFixture().findOrCreateDir("jres");
        VirtualFile kotlinRes = myFixture.getTempDirFixture().findOrCreateDir("kres");
        PsiTestUtil.addSourceRoot(module(), javaRes, JavaResourceRootType.RESOURCE);
        PsiTestUtil.addSourceRoot(module(), kotlinRes, ResourceKotlinRootType.INSTANCE);

        List<String> dirs = build().moduleSourceSets().stream()
                .flatMap(s -> s.sourceDirectories().stream()).toList();
        assertFalse(dirs.contains(javaRes.getPath()));
        assertFalse(dirs.contains(kotlinRes.getPath()));
    }

    /** A test source root becomes its own source set, with test=true. */
    public void testTestSourceRootIsScopedAsTest() throws Exception {
        VirtualFile testDir = myFixture.getTempDirFixture().findOrCreateDir("tst");
        PsiTestUtil.addSourceRoot(module(), testDir, true);

        AnalysisModel.AnalyzeConfig config = build();
        assertTrue("the added test source root should appear in a set with test=true",
                config.moduleSourceSets().stream()
                        .anyMatch(s -> s.test() && s.sourceDirectories().contains(testDir.getPath())));
    }

    /**
     * ⛔ THE INVERTED ONE. Compiler output is the set's identity, NOT a class-path entry.
     * <p>
     * Listing a module's own output on a class path is what let one FQN arrive as both source and bytecode;
     * under self-analysis that produced {@code Cannot commit … has a null parent class} on types whose
     * supertype came back from the class file. The output must be reported, and reported as {@code outputPath}.
     */
    public void testCompilerOutputIsIdentityNotClasspath() throws Exception {
        VirtualFile out = myFixture.getTempDirFixture().findOrCreateDir("out");
        VirtualFile testOut = myFixture.getTempDirFixture().findOrCreateDir("out-test");
        PsiTestUtil.setCompilerOutputPath(module(), out.getUrl(), false);
        PsiTestUtil.setCompilerOutputPath(module(), testOut.getUrl(), true);
        PsiTestUtil.addSourceRoot(module(), myFixture.getTempDirFixture().findOrCreateDir("tst2"), true);

        AnalysisModel.AnalyzeConfig config = build();
        Map<String, AnalysisModel.ModuleSourceSet> sets = sets(config);
        String mainName = main(config).name();
        assertEquals("main output is the main set's identity", out.getPath(), sets.get(mainName).outputPath());

        AnalysisModel.ModuleSourceSet testSet = config.moduleSourceSets().stream()
                .filter(AnalysisModel.ModuleSourceSet::test).findFirst().orElse(null);
        assertNotNull("expected a test set once a test source root exists", testSet);
        assertEquals("test output is the test set's identity", testOut.getPath(), testSet.outputPath());

        List<String> classPaths = allClassPaths(config);
        assertFalse("a module's own output must not be on any set's class path: " + classPaths,
                classPaths.contains(out.getPath()));
        assertFalse("a module's own test output must not be on any set's class path: " + classPaths,
                classPaths.contains(testOut.getPath()));
    }

    /** Library class roots land on the class path of the module that declares them. */
    public void testLibraryClassesOnOwningModuleClassPath() throws Exception {
        VirtualFile libClasses = myFixture.getTempDirFixture().findOrCreateDir("lib-classes");
        PsiTestUtil.addProjectLibrary(module(), "maddi-test-lib", libClasses);

        AnalysisModel.AnalyzeConfig config = build();
        assertTrue("the library should be on the declaring module's own class path: " + allClassPaths(config),
                allClassPaths(config).contains(libClasses.getPath()));
    }
}
