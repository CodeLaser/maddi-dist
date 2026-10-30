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

package io.codelaser.maddi.mcp;

import io.codelaser.maddi.inspection.api.resource.BuildFiles;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A Gradle build's configuration, made without the maddi plugin: compile every Java source set with javac's arguments
 * logged ({@code compile-all.init.gradle.kts}), keep those lines, and let the daemon turn them into the configuration
 * with the build files hashed: the compile-log route of the CLI. Slow (a full compile), so it runs only when the
 * build files changed.
 */
public final class GradleCapture {
    private GradleCapture() {
    }

    /** What a builtin regeneration is called in a stored project. */
    public static final String BUILTIN = "gradle";

    /** The marker of a javac invocation in Gradle's debug output, as the compile-log parser reads it. */
    static final String COMPILER_ARGUMENTS = "Compiler arguments: ";

    /** A build the capture can run: a settings or build script at its root. */
    public static boolean isGradleBuild(Path root) {
        return List.of("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts").stream()
                .anyMatch(f -> Files.isRegularFile(root.resolve(f)));
    }

    /**
     * Compile the build, writing javac's invocations to {@code compileLog} and the rest of Gradle's output (its
     * lifecycle, warnings and errors; not its debug chatter) to {@code buildLog}.
     *
     * @param progress hears how many compilations went by
     * @param attach   is handed the Gradle process, so a stop can end it
     * @return how many compilations were logged
     */
    public static int compile(Path root, Path compileLog, Path buildLog, Consumer<String> progress,
                              Consumer<Process> attach) throws IOException, InterruptedException {
        Path initScript = buildLog.resolveSibling("compile-all.init.gradle.kts");
        try (InputStream in = GradleCapture.class.getResourceAsStream("compile-all.init.gradle.kts")) {
            if (in == null) throw new IOException("compile-all.init.gradle.kts is missing from maddi-mcp's jar");
            Files.copy(in, initScript, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> command = new ArrayList<>(List.of(gradle(root), "-I", initScript.toString(), "--debug",
                "--no-build-cache", "--no-configuration-cache", "--console=plain", "maddiCompileAll"));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        attach.accept(process);
        int compilations = 0;
        try (var reader = process.inputReader(StandardCharsets.UTF_8);
             BufferedWriter compile = Files.newBufferedWriter(compileLog);
             BufferedWriter build = Files.newBufferedWriter(buildLog)) {
            build.write("$ " + String.join(" ", command) + "\n");
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.contains(COMPILER_ARGUMENTS)) {
                    compile.write(line);
                    compile.newLine();
                    progress.accept(++compilations + " compilation(s) logged");
                } else if (!line.contains(" [DEBUG] ") && !line.contains(" [INFO] ")) {
                    build.write(line);
                    build.newLine();
                }
            }
        } finally {
            attach.accept(null);
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("the Gradle build failed (exit " + exit + "); its output is in " + buildLog);
        }
        if (compilations == 0) {
            throw new IOException("the Gradle build compiled no Java source set; its output is in " + buildLog);
        }
        return compilations;
    }

    /** The build files whose change makes the configuration stale: those Gradle reads to configure the build. */
    public static List<String> buildFileGlobs() {
        return BuildFiles.GRADLE_GLOBS;
    }

    /* the build's wrapper, else the gradle on the PATH */
    private static String gradle(Path root) {
        Path wrapper = root.resolve("gradlew");
        return Files.isExecutable(wrapper) ? wrapper.toString() : "gradle";
    }
}
