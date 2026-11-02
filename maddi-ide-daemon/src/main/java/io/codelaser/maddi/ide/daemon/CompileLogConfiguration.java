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

package io.codelaser.maddi.ide.daemon;

import io.codelaser.maddi.inspection.api.resource.BuildFiles;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.project.config.CompileLogCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A compile log turned into an {@code inputConfiguration.json}, with its build files hashed now: the CLI's
 * {@code --compile-log --build-files --write-input-configuration}, step for step (Java's javac invocations only, as
 * the Java CLI), so the two write the same file from the same log. The work is maddi-project-config's
 * {@link CompileLogCapture#fromCompileLog}, which maddi-mcp's captures use too.
 */
final class CompileLogConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompileLogConfiguration.class);

    private CompileLogConfiguration() {
    }

    static DaemonProtocol.InputConfigurationWritten write(DaemonProtocol.WriteInputConfiguration request)
            throws IOException {
        Path log = Path.of(request.compileLog());
        List<String> jmods = request.extraJmods() == null ? List.of() : request.extraJmods();
        List<String> globs = request.buildFileGlobs() == null ? List.of() : request.buildFileGlobs();
        Path output = Path.of(request.output());
        InputConfiguration configuration = CompileLogCapture.fromCompileLog(log, Path.of(request.buildRoot()), globs,
                jmods, output);
        BuildFiles buildFiles = configuration.buildFiles();
        LOGGER.info("wrote {}: {} source set(s) from {}, {} build file(s) hashed", output,
                configuration.sourceSets().size(), log, buildFiles.sha256().size());
        return new DaemonProtocol.InputConfigurationWritten(request.requestId(), output.toString(),
                configuration.sourceSets().size(), buildFiles.sha256().size());
    }
}
