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
import io.codelaser.maddi.run.main.Main;
import io.codelaser.maddi.run.openjdkmain.RunAnalyzer;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The actual analysis, run in a forked worker JVM (see {@link AnalyzerTask}) so the openjdk front-end's required
 * {@code --add-exports} for {@code com.sun.tools.javac.*} can be injected without the consuming build's daemon
 * needing any special configuration. Deserializes the {@link Configuration} and hands it to the openjdk-based
 * {@link RunAnalyzer}.
 */
public abstract class AnalyzerWorkAction implements WorkAction<AnalyzerWorkAction.Parameters> {

    public interface Parameters extends WorkParameters {
        Property<String> getConfigurationJson();
    }

    @Override
    public void execute() {
        String configurationJson = getParameters().getConfigurationJson().get();
        ObjectMapper objectMapper = JsonStreaming.objectMapper();
        Configuration configuration;
        try {
            configuration = objectMapper.readerFor(Configuration.class).readValue(configurationJson);
        } catch (IOException ioException) {
            throw new UncheckedIOException("Cannot read the e2immu configuration", ioException);
        }
        RunAnalyzer runAnalyzer = new RunAnalyzer(configuration);
        runAnalyzer.run();
        int exitValue = runAnalyzer.exitValue();
        if (exitValue != Main.EXIT_OK) {
            throw new RuntimeException("e2immu analyzer failed (exit " + exitValue + "): " + Main.exitMessage(exitValue));
        }
    }
}
