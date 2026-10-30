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

import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.inspection.api.resource.BuildFiles;
import io.codelaser.maddi.inspection.resource.BuildFilesCapture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether a stored configuration still describes the build: the files that shaped it, hashed when it was written
 * ({@link BuildFiles}, the {@code buildFiles} of an {@code inputConfiguration.json}), hashed again now. The same
 * capture recomputes them, so the two agree on which files count and how they are named.
 */
public final class BuildFileCheck {
    private BuildFileCheck() {
    }

    public enum State {
        /** Every recorded build file hashes as it did. */
        FRESH,
        /** A build file changed, appeared or went: the configuration may no longer be what the build compiles. */
        STALE,
        /** Nothing was recorded to compare with. */
        UNCHECKED
    }

    /** @param changed the files that differ, relative to the capture's root, each with what happened to it */
    public record Freshness(State state, List<String> changed) {
        public Map<String, Object> describe() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("buildFiles", state.name());
            if (!changed.isEmpty()) out.put("changedBuildFiles", changed.size() > 20
                    ? List.copyOf(changed.subList(0, 20)) + " and " + (changed.size() - 20) + " more" : changed);
            return out;
        }
    }

    public static Freshness check(BuildFiles recorded, AnalysisModel.AnalyzeConfig config) {
        if (recorded == null || recorded.root() == null
            || recorded.sha256().isEmpty() && recorded.globs().isEmpty()) {
            return new Freshness(State.UNCHECKED, List.of());
        }
        Path root = Path.of(recorded.root());
        // the recorded files by name as well as by glob: a file outside the root (an included build, '../x') is
        // found by no glob
        List<Path> named = recorded.sha256().keySet().stream().map(root::resolve).toList();
        BuildFiles now = BuildFilesCapture.capture(recorded.declaredBy(), root, recorded.globs(), named);
        List<String> changed = new ArrayList<>();
        for (var entry : recorded.sha256().entrySet()) {
            String hash = now.sha256().get(entry.getKey());
            if (hash == null) changed.add(entry.getKey() + " (gone)");
            else if (!hash.equals(entry.getValue())) changed.add(entry.getKey() + " (changed)");
        }
        for (String key : now.sha256().keySet()) {
            if (!recorded.sha256().containsKey(key)) changed.add(key + " (new)");
        }
        // a module-info is recorded where there was one; one that appeared since has no glob to be found by
        for (Path moduleInfo : moduleInfos(config)) {
            String key = root.relativize(moduleInfo).toString().replace(moduleInfo.getFileSystem().getSeparator(), "/");
            if (!recorded.sha256().containsKey(key) && !now.sha256().containsKey(key)) changed.add(key + " (new)");
        }
        return new Freshness(changed.isEmpty() ? State.FRESH : State.STALE, List.copyOf(changed));
    }

    /**
     * What to record for a configuration that no build tool wrote: the Gradle and Maven files under its working
     * directory and its module-infos, so a change to the build is noticed even though it cannot be redone.
     */
    public static BuildFiles capture(AnalysisModel.AnalyzeConfig config) {
        List<String> globs = new ArrayList<>(BuildFiles.GRADLE_GLOBS);
        globs.addAll(BuildFiles.MAVEN_GLOBS);
        return BuildFilesCapture.capture(BuildFiles.NONE, Path.of(config.workingDirectory()), globs,
                moduleInfos(config));
    }

    private static List<Path> moduleInfos(AnalysisModel.AnalyzeConfig config) {
        List<Path> out = new ArrayList<>();
        for (AnalysisModel.ModuleSourceSet set : config.moduleSourceSets()) {
            for (String dir : set.sourceDirectories()) {
                Path moduleInfo = Path.of(dir).resolve("module-info.java").toAbsolutePath().normalize();
                if (Files.isRegularFile(moduleInfo)) out.add(moduleInfo);
            }
        }
        return out;
    }
}
