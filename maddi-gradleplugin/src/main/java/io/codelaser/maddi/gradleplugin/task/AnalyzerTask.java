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

package io.codelaser.maddi.gradleplugin.task;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.process.ExecOperations;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

/**
 * Runs the maddi analyzer over the project's sources. It deserializes the {@link io.codelaser.maddi.run.config.Configuration}
 * computed by the plugin (source sets, classpath, analysis steps, results directory), serialized to
 * {@link #getConfigurationJson()}, and hands it to the openjdk-parser-based
 * {@link io.codelaser.maddi.run.openjdkmain.RunAnalyzer}, which parses, runs the prep- and modification-analysis,
 * and writes the results to {@link #getAnalysisResultsDir()} (default {@code <build>/e2immu}).
 * <p>
 * When a source directory holds Kotlin (and {@code skipKotlinSources} is not set), the Kotlin-capable analyzer runs
 * instead, as a child process of a distribution that is downloaded or pointed at: see {@link KotlinAnalyzerFork}.
 * <p>
 * The Java-only analysis runs in a <em>forked</em> worker JVM (see {@link AnalyzerWorkAction}): the openjdk front-end reaches
 * into {@code jdk.compiler}'s {@code com.sun.tools.javac.*} internals, which need {@code --add-exports}. Injecting
 * them into a forked worker keeps the consuming build's Gradle daemon unaffected.
 * <p>
 * The task holds no {@code Project} reference at execution time and uses lazy managed properties, so it is
 * configuration-cache compatible and declares its inputs/outputs for up-to-date checking and build caching.
 */
@CacheableTask
public abstract class AnalyzerTask extends DefaultTask {
    private static final Logger LOGGER = Logging.getLogger(AnalyzerTask.class);

    // the openjdk front-end (JavacFileManager / Trees / Symbols) reaches into these javac internals
    private static final List<String> ADD_EXPORTS = List.of(
            "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
            // SharedArchives: one shared copy of each jar across javac file managers
            "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED");

    /** The serialized {@code run-config} {@link io.codelaser.maddi.run.config.Configuration}. */
    @Input
    public abstract Property<String> getConfigurationJson();

    /** The source directories and classpath the analysis reads, for up-to-date checking. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getAnalyzedInputs();

    /** Where the analyzer writes its results (matches {@code analysisResultsDir} in the configuration). */
    @OutputDirectory
    public abstract DirectoryProperty getAnalysisResultsDir();

    /** Working directory of the forked analyzer; the analyzer resolves relative source-set paths against it. */
    @Internal
    public abstract DirectoryProperty getWorkingDirectory();

    /** The maddi version this plugin was built with: the release whose Kotlin distribution is downloaded. */
    @Input
    public abstract Property<String> getMaddiVersion();

    /** {@code maddi.kotlinDistribution}: a local distribution directory or zip, instead of the download. */
    @Input
    @Optional
    public abstract Property<String> getKotlinDistribution();

    /** {@code maddi.kotlinJavaHome}: the JDK for the Kotlin-capable analyzer, instead of a toolchain. */
    @Input
    @Optional
    public abstract Property<String> getKotlinJavaHome();

    /** {@code maddi.kotlinJavaVersion}: the toolchain version for the Kotlin-capable analyzer. */
    @Input
    public abstract Property<Integer> getKotlinJavaVersion();

    /** {@code maddi.kotlinJvmArgs}, space-separated. */
    @Input
    @Optional
    public abstract Property<String> getKotlinJvmArgs();

    /** Where downloaded and unpacked Kotlin distributions are kept, shared by every build of this user. */
    @Internal
    public abstract DirectoryProperty getKotlinCacheDirectory();

    @Inject
    public abstract WorkerExecutor getWorkerExecutor();

    @Inject
    public abstract ExecOperations getExecOperations();

    // ⚠ injected, not a @Nested Provider<JavaLauncher>: a nested input is resolved before every run, and a Java-only
    // project must not fail for want of a JDK that only the Kotlin path uses
    @Inject
    public abstract JavaToolchainService getJavaToolchainService();

    @TaskAction
    public void run() {
        String configurationJson = getConfigurationJson().get();
        File workingDir = getWorkingDirectory().getAsFile().getOrNull();
        KotlinAnalyzerFork.Route route = KotlinAnalyzerFork.route(KotlinAnalyzerFork.read(configurationJson));
        if (route.kotlin()) {
            runKotlinAnalyzer(configurationJson, workingDir, route);
            return;
        }
        LOGGER.info("Running the e2immu analyzer in a forked worker, working directory {}", workingDir);
        WorkQueue workQueue = getWorkerExecutor().processIsolation(spec -> {
            spec.getForkOptions().jvmArgs(ADD_EXPORTS);
            // the analyzer resolves the (relative) source-set paths against the process working directory
            if (workingDir != null) spec.getForkOptions().setWorkingDir(workingDir);
        });
        workQueue.submit(AnalyzerWorkAction.class, params -> params.getConfigurationJson().set(configurationJson));
        workQueue.await(); // surface analyzer failures as a build failure of this task
    }

    private void runKotlinAnalyzer(String configurationJson, File workingDir, KotlinAnalyzerFork.Route route) {
        Path distribution = KotlinAnalyzerFork.distribution(getKotlinDistribution().getOrNull(),
                getKotlinCacheDirectory().get().getAsFile().toPath(), getMaddiVersion().get());
        String java = javaExecutable();
        LOGGER.lifecycle("{} source director{} hold Kotlin: running the maddi Kotlin analyzer from {} on {}",
                route.kotlinSourceDirectories(), route.kotlinSourceDirectories() == 1 ? "y" : "ies", distribution,
                java);
        KotlinAnalyzerFork.run(getExecOperations(), java, distribution, configurationJson, workingDir,
                getTemporaryDir().toPath(),
                KotlinAnalyzerFork.jvmArgs(ADD_EXPORTS, distribution, getKotlinJvmArgs().getOrNull()));
    }

    private String javaExecutable() {
        String javaHome = getKotlinJavaHome().getOrNull();
        if (javaHome != null && !javaHome.isBlank()) {
            Path java = Path.of(javaHome, "bin", isWindows() ? "java.exe" : "java");
            if (!Files.isExecutable(java)) {
                throw new GradleException("kotlinJavaHome " + javaHome + " holds no bin/java");
            }
            return java.toString();
        }
        int version = getKotlinJavaVersion().get();
        try {
            return getJavaToolchainService().launcherFor(spec -> spec.getLanguageVersion()
                    .set(JavaLanguageVersion.of(version))).get().getExecutablePath().getAsFile().getAbsolutePath();
        } catch (RuntimeException e) {
            throw new GradleException("The maddi Kotlin analyzer needs a Java " + version + " toolchain, and none was"
                                      + " found: " + e.getMessage() + ". Set maddi.kotlinJavaHome, or"
                                      + " maddi.skipKotlinSources = true to analyze the Java sources alone.", e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }
}
