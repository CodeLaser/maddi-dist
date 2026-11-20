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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

import io.codelaser.maddi.ide.client.KotlinFrontEndInstaller;
import io.codelaser.maddi.run.config.Configuration;
import io.codelaser.maddi.run.config.report.ExitCode;
import io.codelaser.maddi.run.config.util.JsonStreaming;

/**
 * Runs the Kotlin-capable analyzer ({@code maddi-kotlin}, the mixed Java+Kotlin driver) as a plain child process.
 * <p>
 * ⚠ Not a Gradle worker, as the Java-only analyzer is, for two reasons. The K2 compiler is not on Maven Central, so
 * it cannot ride in this plugin's jar or its dependencies; it comes from the CLI distribution, downloaded or pointed
 * at. And that distribution's classes target Java 26, newer than what a Gradle daemon may run (or instrument), so it
 * gets a JDK of its own from a toolchain. The driver reads the whole {@link Configuration} from a file
 * ({@code RunConfigurationFile}), so this plugin's options mean the same on both paths.
 */
final class KotlinAnalyzerFork {
    private static final Logger LOGGER = Logging.getLogger(KotlinAnalyzerFork.class);

    static final String MAIN_CLASS = "io.codelaser.maddi.run.kotlinmain.RunConfigurationFile";
    /** {@code K2Realm.HOME_PROPERTY}: the directory of K2 jars the driver loads into a realm of its own. */
    static final String K2_HOME_PROPERTY = "maddi.k2.home";

    private KotlinAnalyzerFork() {
    }

    /** What the Kotlin path needs to know about the configuration: whether to take it at all. */
    record Route(boolean kotlin, int kotlinSourceDirectories) {
    }

    /**
     * The Kotlin-capable analyzer runs when a source directory holds a {@code .kt} file and
     * {@code skipKotlinSources} is not set. Decided at execution time, from the directories the configuration
     * names, so a project that gains its first Kotlin file needs no reconfiguration.
     */
    static Route route(Configuration configuration) {
        if (configuration.generalConfiguration().skipKotlinSources()) return new Route(false, 0);
        // source directories may be relative to the configuration's working directory, never to this daemon's
        Path workingDirectory = configuration.inputConfiguration().workingDirectory();
        List<String> directories = new ArrayList<>();
        configuration.inputConfiguration().sourceSets().forEach(set -> {
            if (!set.externalLibrary() && set.sourceDirectories() != null) {
                set.sourceDirectories().forEach(d -> directories.add(
                        (workingDirectory == null ? d : workingDirectory.resolve(d)).toString()));
            }
        });
        int withKotlin = 0;
        for (String directory : directories) {
            if (KotlinFrontEndInstaller.anyKotlinSource(List.of(directory))) withKotlin++;
        }
        return new Route(withKotlin > 0, withKotlin);
    }

    static Configuration read(String configurationJson) {
        try {
            return JsonStreaming.objectMapper().readerFor(Configuration.class).readValue(configurationJson);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the maddi configuration", e);
        }
    }

    /**
     * The distribution home: the user's directory as is, the user's zip unpacked into the cache, or the release of
     * {@code maddiVersion} downloaded into it.
     */
    static Path distribution(String override, Path cacheDir, String maddiVersion) {
        KotlinFrontEndInstaller installer = new KotlinFrontEndInstaller(cacheDir);
        try {
            if (override != null && !override.isBlank()) {
                Path given = Path.of(override).toAbsolutePath();
                if (Files.isDirectory(given)) {
                    if (!KotlinFrontEndInstaller.holdsDistribution(given)) {
                        throw new GradleException("kotlinDistribution " + given + " is not an unpacked maddi-kotlin"
                                                  + " distribution: it needs lib/ and lib-k2/");
                    }
                    return given;
                }
                if (Files.isRegularFile(given)) return installer.installDistribution(given);
                throw new GradleException("kotlinDistribution " + given + " does not exist");
            }
            Path installed = installer.installedDistribution(maddiVersion);
            if (installed != null) return installed;
            LOGGER.lifecycle("Downloading the maddi {} Kotlin analyzer from {}", maddiVersion,
                    KotlinFrontEndInstaller.releaseZip(maddiVersion));
            return installer.installDistribution(maddiVersion, null);
        } catch (IOException e) {
            throw new GradleException("Cannot install the maddi Kotlin analyzer: " + e.getMessage()
                                      + ". Set maddi.kotlinDistribution to a local maddi-kotlin distribution, or"
                                      + " maddi.skipKotlinSources = true to analyze the Java sources alone.", e);
        }
    }

    static List<File> classPath(Path distribution) {
        try (Stream<Path> jars = Files.list(distribution.resolve("lib"))) {
            return jars.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().map(Path::toFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> jvmArgs(List<String> addExports, Path distribution, String extraJvmArgs) {
        List<String> args = new ArrayList<>(addExports);
        args.add("-D" + K2_HOME_PROPERTY + "=" + distribution.resolve("lib-k2").toAbsolutePath());
        if (extraJvmArgs != null && !extraJvmArgs.isBlank()) {
            args.addAll(List.of(extraJvmArgs.trim().split("\\s+")));
        }
        return args;
    }

    static void run(ExecOperations execOperations, String javaExecutable, Path distribution, String configurationJson,
                    File workingDir, Path temporaryDir, List<String> jvmArgs) {
        Path configFile;
        try {
            Files.createDirectories(temporaryDir);
            configFile = temporaryDir.resolve("configuration.json");
            Files.writeString(configFile, configurationJson);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the maddi configuration for the Kotlin analyzer", e);
        }
        ExecResult result = execOperations.javaexec(spec -> {
            spec.setExecutable(javaExecutable);
            spec.classpath(classPath(distribution));
            spec.getMainClass().set(MAIN_CLASS);
            spec.jvmArgs(jvmArgs);
            spec.args(configFile.toAbsolutePath().toString());
            if (workingDir != null) spec.setWorkingDir(workingDir);
            spec.setIgnoreExitValue(true);
        });
        int exitValue = result.getExitValue();
        if (exitValue != ExitCode.OK) {
            throw new GradleException("maddi Kotlin analyzer failed (exit " + exitValue + "): "
                                      + ExitCode.message(exitValue));
        }
    }
}
