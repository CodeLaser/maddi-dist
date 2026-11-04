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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.inspection.api.resource.BuildFiles;
import io.codelaser.maddi.project.config.BuildFileCheck;
import io.codelaser.maddi.project.config.Regenerate;

/**
 * The daemon's per-module {@link AnalysisModel.AnalyzeConfig}, built without an IDE: from the
 * {@code inputConfiguration.json} that the Gradle plugin's {@code maddi-write-input-configuration} task and the
 * Maven plugin's {@code write-input-configuration} goal write, or from directories an agent names (a build with neither
 * plugin: Ant, a hand-rolled one).
 */
public final class ProjectConfigs {
    private ProjectConfigs() {
    }

    /**
     * From an {@code inputConfiguration.json}: its {@code sourceSets} become the module source sets, each keeping its
     * own dependencies, split into the other source sets it names and the library parts it names (jars and class
     * directories, by path). JDK parts become {@code jmods}. A runtime-only library is left out: nothing compiles
     * against it.
     */
    public static AnalysisModel.AnalyzeConfig fromInputConfiguration(Path file, ObjectMapper mapper)
            throws IOException {
        JsonNode root = mapper.readTree(file.toFile());
        String workingDirectory = text(root, "workingDirectory");
        Path base = workingDirectory == null ? file.toAbsolutePath().getParent() : Path.of(workingDirectory);

        Map<String, String> libraryPaths = new HashMap<>();
        Set<String> jdkModules = new LinkedHashSet<>();
        Set<String> runtimeOnly = new HashSet<>();
        for (JsonNode part : root.path("classPathParts")) {
            String name = text(part, "name");
            if (name == null) continue;
            if (part.path("partOfJdk").asBoolean(false)) {
                jdkModules.add(name);
            } else {
                String path = pathOf(text(part, "uri"));
                if (path != null) libraryPaths.put(name, path);
                if (part.path("runtimeOnly").asBoolean(false)) runtimeOnly.add(name);
            }
        }
        Set<String> setNames = new HashSet<>();
        for (JsonNode set : root.path("sourceSets")) setNames.add(text(set, "name"));

        String encoding = null;
        List<AnalysisModel.ModuleSourceSet> sets = new ArrayList<>();
        for (JsonNode set : root.path("sourceSets")) {
            if (encoding == null) encoding = text(set, "sourceEncoding");
            List<String> dirs = new ArrayList<>();
            for (JsonNode dir : set.path("sourceDirectories")) dirs.add(base.resolve(dir.asText()).toString());
            List<String> dependencies = new ArrayList<>();
            List<String> classPath = new ArrayList<>();
            for (JsonNode dep : set.path("dependencies")) {
                String name = dep.asText();
                if (setNames.contains(name)) {
                    dependencies.add(name);
                } else if (libraryPaths.containsKey(name) && !runtimeOnly.contains(name)) {
                    classPath.add(libraryPaths.get(name));
                }
                // JDK modules are named in jmods, once for every set
            }
            sets.add(new AnalysisModel.ModuleSourceSet(text(set, "name"), dirs, pathOf(text(set, "uri")),
                    set.path("test").asBoolean(false), set.path("sourceRelease").asInt(0), dependencies, classPath));
        }
        if (sets.isEmpty()) throw new IOException(file + " declares no source sets");
        return new AnalysisModel.AnalyzeConfig(base.toString(), text(root, "alternativeJREDirectory"),
                encoding == null ? "UTF-8" : encoding, List.copyOf(jdkModules), List.of(), List.of(), List.of(),
                false, false, sets);
    }

    /** The {@code buildFiles} a build tool recorded with the configuration; null when it recorded none. */
    public static BuildFiles buildFilesOf(Path file, ObjectMapper mapper) throws IOException {
        JsonNode node = mapper.readTree(file.toFile()).get("buildFiles");
        return node == null || node.isNull() ? null : mapper.treeToValue(node, BuildFiles.class);
    }

    /** The Gradle plugin's task that writes {@code build/inputConfiguration.json}. */
    public static final String GRADLE_TASK = Regenerate.GRADLE_TASK;
    /** The Maven plugin's goal that writes {@code target/inputConfiguration.json}. */
    public static final String MAVEN_GOAL = Regenerate.MAVEN_GOAL;

    /**
     * How to write {@code file} again, from who wrote it: the plugin's task or goal ({@link Regenerate#defaultFor}).
     * Null for any other producer (a compile log, say): then only a command given at configuration can.
     */
    public static ProjectStore.Regenerate defaultRegenerate(BuildFiles buildFiles, Path file) {
        Regenerate regenerate = Regenerate.defaultFor(buildFiles, file);
        return regenerate == null ? null
                : new ProjectStore.Regenerate(regenerate.command(), regenerate.directory().toString());
    }

    /** Whether the build files recorded with a configuration still hash as they did. */
    public static BuildFileCheck.Freshness freshness(BuildFiles recorded, AnalysisModel.AnalyzeConfig config) {
        return BuildFileCheck.check(recorded, config == null ? List.of() : sourceDirectories(config));
    }

    /**
     * What to record for a configuration that no build tool wrote: the Gradle and Maven files under its working
     * directory and its module-infos, so a change to the build is noticed even though it cannot be redone.
     */
    public static BuildFiles buildFilesOf(AnalysisModel.AnalyzeConfig config) {
        return BuildFileCheck.hashDefault(Path.of(config.workingDirectory()), sourceDirectories(config));
    }

    private static List<Path> sourceDirectories(AnalysisModel.AnalyzeConfig config) {
        List<Path> out = new ArrayList<>();
        for (AnalysisModel.ModuleSourceSet set : config.moduleSourceSets()) {
            for (String dir : set.sourceDirectories()) out.add(Path.of(dir));
        }
        return out;
    }

    /**
     * From directories: one {@code main} source set, and a {@code test} one depending on it when test directories
     * are given. Relative paths are taken against {@code workingDirectory}.
     *
     * @param outputDirectory the main set's compiled classes, if any; it is the set's identity, not a class-path part
     */
    public static AnalysisModel.AnalyzeConfig fromDirectories(Path workingDirectory, List<String> sourceDirectories,
                                                              List<String> testSourceDirectories,
                                                              List<String> classPath, List<String> testClassPath,
                                                              String outputDirectory, int sourceRelease,
                                                              String sdkHome, List<String> jdkModules,
                                                              String sourceEncoding) {
        if (sourceDirectories.isEmpty()) throw new IllegalArgumentException("no source directories");
        List<AnalysisModel.ModuleSourceSet> sets = new ArrayList<>();
        sets.add(new AnalysisModel.ModuleSourceSet("main", resolve(workingDirectory, sourceDirectories),
                outputDirectory == null ? null : workingDirectory.resolve(outputDirectory).toString(), false,
                sourceRelease, List.of(), resolve(workingDirectory, classPath)));
        if (!testSourceDirectories.isEmpty()) {
            List<String> testPath = new ArrayList<>(resolve(workingDirectory, classPath));
            testPath.addAll(resolve(workingDirectory, testClassPath));
            sets.add(new AnalysisModel.ModuleSourceSet("test", resolve(workingDirectory, testSourceDirectories),
                    null, true, sourceRelease, List.of("main"), testPath));
        }
        return new AnalysisModel.AnalyzeConfig(workingDirectory.toString(), sdkHome,
                sourceEncoding == null ? "UTF-8" : sourceEncoding, jdkModules, List.of(), List.of(), List.of(),
                false, false, sets);
    }

    /**
     * The part of a configured project to analyse. {@code sourceSets}, when not empty, keeps the named sets; a kept
     * set that depends on a dropped one reads it from its compiled output instead, so it must have been built.
     * {@code packages} restricts the sources parsed (a package, or a prefix ending in a dot).
     */
    public static AnalysisModel.AnalyzeConfig part(AnalysisModel.AnalyzeConfig config, List<String> sourceSets,
                                                   List<String> packages) {
        List<AnalysisModel.ModuleSourceSet> sets = config.moduleSourceSets();
        if (!sourceSets.isEmpty()) {
            Map<String, AnalysisModel.ModuleSourceSet> byName = new HashMap<>();
            for (AnalysisModel.ModuleSourceSet set : sets) byName.put(set.name(), set);
            List<String> unknown = sourceSets.stream().filter(n -> !byName.containsKey(n)).toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("unknown source set(s) " + unknown + "; the project has "
                                                   + byName.keySet());
            }
            Set<String> kept = Set.copyOf(sourceSets);
            List<AnalysisModel.ModuleSourceSet> narrowed = new ArrayList<>();
            for (AnalysisModel.ModuleSourceSet set : sets) {
                if (!kept.contains(set.name())) continue;
                List<String> dependencies = new ArrayList<>();
                List<String> classPath = new ArrayList<>(set.classPath());
                for (String dep : dropped(set.name(), byName, kept)) {
                    AnalysisModel.ModuleSourceSet droppedSet = byName.get(dep);
                    if (droppedSet.outputPath() != null) classPath.add(droppedSet.outputPath());
                    classPath.addAll(droppedSet.classPath());
                }
                for (String dep : set.dependencies()) if (kept.contains(dep)) dependencies.add(dep);
                narrowed.add(new AnalysisModel.ModuleSourceSet(set.name(), set.sourceDirectories(), set.outputPath(),
                        set.test(), set.sourceRelease(), dependencies, List.copyOf(new LinkedHashSet<>(classPath))));
            }
            sets = narrowed;
        }
        return new AnalysisModel.AnalyzeConfig(config.workingDirectory(), config.sdkHome(), config.sourceEncoding(),
                config.jmods(), config.sources(), config.classpath(), List.copyOf(packages), config.parallel(),
                config.warnNearMisses(), sets);
    }

    /*
     The dropped sets this one reaches, directly or through other dropped sets: a kept set's own dependencies come
     along as sets, so only the dropped part of the closure turns into class files, with the libraries those sets
     brought, which the kept set no longer reaches through them.
     */
    private static Set<String> dropped(String from, Map<String, AnalysisModel.ModuleSourceSet> byName,
                                       Set<String> kept) {
        Set<String> out = new LinkedHashSet<>();
        List<String> todo = new ArrayList<>(byName.get(from).dependencies());
        while (!todo.isEmpty()) {
            String dep = todo.removeLast();
            if (kept.contains(dep) || !byName.containsKey(dep) || !out.add(dep)) continue;
            todo.addAll(byName.get(dep).dependencies());
        }
        return out;
    }

    /** The {@code java.*} modules of a JDK, by asking its launcher: what a project may compile against. */
    public static List<String> jdkModules(Path jdkHome) throws IOException {
        Process p = new ProcessBuilder(jdkHome.resolve("bin/java").toString(), "--list-modules")
                .redirectErrorStream(true).start();
        List<String> out = new ArrayList<>();
        try (var reader = p.inputReader()) {
            for (String line; (line = reader.readLine()) != null; ) {
                String name = line.strip();
                int at = name.indexOf('@');
                if (at > 0) name = name.substring(0, at);
                if (name.startsWith("java.")) out.add(name);
            }
        }
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (out.isEmpty()) throw new IOException("no java.* modules listed by " + jdkHome);
        return List.copyOf(out);
    }

    /** Fails when a source directory is missing: an analysis of nothing is otherwise reported as clean. */
    public static void check(AnalysisModel.AnalyzeConfig config) throws IOException {
        for (AnalysisModel.ModuleSourceSet set : config.moduleSourceSets()) {
            for (String dir : set.sourceDirectories()) {
                if (!Files.isDirectory(Path.of(dir))) {
                    throw new IOException("source set " + set.name() + ": no directory " + dir);
                }
            }
        }
    }

    private static List<String> resolve(Path base, List<String> paths) {
        return paths.stream().map(p -> base.resolve(p).toString()).toList();
    }

    private static String pathOf(String uri) {
        if (uri == null || uri.isBlank()) return null;
        return uri.startsWith("file:") ? Path.of(URI.create(uri)).toString() : uri;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
