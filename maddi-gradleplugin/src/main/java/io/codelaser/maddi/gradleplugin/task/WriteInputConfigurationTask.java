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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codelaser.maddi.run.config.Configuration;
import io.codelaser.maddi.run.config.util.JsonStreaming;
import io.codelaser.maddi.inspection.api.resource.BuildFiles;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.inspection.resource.BuildFilesCapture;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the project's {@link InputConfiguration} (extracted from the plugin-computed
 * {@link Configuration}) to a JSON file. Modern lazy-property task: configuration-cache compatible, holds no
 * {@code Project} at execution.
 */
@DisableCachingByDefault(because = "Trivial JSON re-serialization; cheaper to re-run than to cache and load.")
public abstract class WriteInputConfigurationTask extends DefaultTask {
    private static final Logger LOGGER = Logging.getLogger(WriteInputConfigurationTask.class);

    /** The serialized {@code run-config} {@link Configuration}; its input configuration is written out. */
    @Input
    public abstract Property<String> getConfigurationJson();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /**
     * The build's root directory, where its settings file is: the root of the configuration's {@link BuildFiles}.
     * Internal: the files are hashed when the task runs, so a capture is never older than what it says it read.
     */
    @Internal
    public abstract Property<String> getBuildRoot();

    /** The root directories of the builds included from outside the root (a shared build-logic): hashed by ../ path. */
    @Internal
    public abstract ListProperty<String> getIncludedBuildRoots();

    @TaskAction
    public void run() {
        ObjectMapper objectMapper = JsonStreaming.objectMapper();
        File outputFile = getOutputFile().getAsFile().get();
        try {
            Configuration configuration = objectMapper.readerFor(Configuration.class)
                    .readValue(getConfigurationJson().get());
            InputConfiguration read = configuration.inputConfiguration();
            InputConfiguration inputConfiguration = read.withBuildFiles(buildFiles(read));
            File parent = outputFile.getParentFile();
            if (parent != null && parent.mkdirs()) {
                LOGGER.debug("Created parent directories for output file {}", outputFile);
            }
            objectMapper.writerFor(InputConfigurationImpl.class)
                    .withDefaultPrettyPrinter()
                    .writeValue(outputFile, inputConfiguration);
        } catch (IOException ioException) {
            throw new UncheckedIOException("Cannot write the input configuration", ioException);
        }
    }

    /*
     The files Gradle read to configure this build, hashed now: the settings file, every build script, the version
     catalogs, gradle.properties, buildSrc and the wrapper properties (BuildFiles.GRADLE_GLOBS); the same in every
     included build outside the root; and the module-info of each of the configuration's source directories.
     */
    private BuildFiles buildFiles(InputConfiguration configuration) {
        Path root = Path.of(getBuildRoot().get());
        List<Path> named = new ArrayList<>();
        for (String included : getIncludedBuildRoots().get()) {
            Path includedRoot = Path.of(included).toAbsolutePath().normalize();
            if (includedRoot.startsWith(root.toAbsolutePath().normalize())) continue; // the globs find it
            BuildFilesCapture.capture(BuildFiles.GRADLE_PLUGIN, includedRoot, BuildFiles.GRADLE_GLOBS, List.of())
                    .sha256().keySet().forEach(relative -> named.add(includedRoot.resolve(relative)));
        }
        return BuildFilesCapture.capture(BuildFiles.GRADLE_PLUGIN, root, BuildFiles.GRADLE_GLOBS, named, configuration);
    }
}
