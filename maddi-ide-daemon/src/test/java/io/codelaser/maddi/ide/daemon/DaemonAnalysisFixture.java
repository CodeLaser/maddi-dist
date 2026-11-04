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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test harness for daemon analysis: write a Java snippet into a temp project, run the real pipeline, and
 * return the plain-JSON result — plus small query helpers so a fixture test is a few lines.
 * <p>
 * The e2immu annotations come from the daemon's own classpath, so snippets need no classpath of their own;
 * only {@code java.base} is on the module list. {@code sdkHome} is the test JVM (JDK 25+).
 */
public final class DaemonAnalysisFixture {
    private DaemonAnalysisFixture() {
    }

    /** Analyze a single source file (path relative to the source root, e.g. {@code "x/Foo.java"}). */
    public static DaemonProtocol.Result analyze(Path projectDir, String relativePath, String source)
            throws Exception {
        return analyze(projectDir, relativePath, source, false);
    }

    /** As {@link #analyze(Path, String, String)}, with the advisory near-miss warnings turned on or off. */
    public static DaemonProtocol.Result analyze(Path projectDir, String relativePath, String source,
                                                boolean warnNearMisses) throws Exception {
        return analyze(projectDir, relativePath, source, warnNearMisses, status -> { });
    }

    /** As {@link #analyze(Path, String, String)}, run by the given service (one configured for the test). */
    public static DaemonProtocol.Result analyze(Path projectDir, String relativePath, String source,
                                                WarmAnalysisService service) throws Exception {
        return analyze(projectDir, relativePath, source, false, status -> { }, service);
    }

    /** As above, with a sink — use it to capture the streamed {@code partialResult} frames. */
    public static DaemonProtocol.Result analyze(Path projectDir, String relativePath, String source,
                                                boolean warnNearMisses, AnalyzeHandler.StatusSink sink)
            throws Exception {
        return analyze(projectDir, relativePath, source, warnNearMisses, sink, new WarmAnalysisService());
    }

    private static DaemonProtocol.Result analyze(Path projectDir, String relativePath, String source,
                                                 boolean warnNearMisses, AnalyzeHandler.StatusSink sink,
                                                 WarmAnalysisService service) throws Exception {
        Path file = projectDir.resolve("src").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        DaemonProtocol.AnalyzeConfig config = new DaemonProtocol.AnalyzeConfig(
                projectDir.toAbsolutePath().toString(),
                System.getProperty("java.home"),
                "UTF-8",
                List.of("java.base"),
                List.of(new DaemonProtocol.SourceRoot("main", "src", false)),
                List.of(),
                List.of(),
                false,
                warnNearMisses);
        return service.analyze(new DaemonProtocol.AnalyzeProject("test", config), sink);
    }

    /**
     * Analyze SEVERAL source files in one project, keyed by path relative to the source root. Needed whenever a
     * test is about how one compilation unit affects another — a partial parse, for instance, where the point is
     * that the file which fails must not silence the file which does not.
     */
    public static DaemonProtocol.Result analyzeAll(Path projectDir, Map<String, String> sourcesByRelativePath)
            throws Exception {
        return analyzeAll(projectDir, sourcesByRelativePath, new WarmAnalysisService(), false);
    }

    /**
     * As above, run by the given service, which may keep its state from an earlier call (-Dmaddi.daemon.warm). Files
     * already in the project stay as they are unless {@code sourcesByRelativePath} names them.
     */
    public static DaemonProtocol.Result analyzeAll(Path projectDir, Map<String, String> sourcesByRelativePath,
                                                   WarmAnalysisService service, boolean warnNearMisses)
            throws Exception {
        return analyzeAll(projectDir, sourcesByRelativePath, service, warnNearMisses, false);
    }

    /** As above; {@code cold}: the request asks for a run from scratch, whatever the service kept. */
    public static DaemonProtocol.Result analyzeAll(Path projectDir, Map<String, String> sourcesByRelativePath,
                                                   WarmAnalysisService service, boolean warnNearMisses, boolean cold)
            throws Exception {
        for (Map.Entry<String, String> entry : sourcesByRelativePath.entrySet()) {
            Path file = projectDir.resolve("src").resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        DaemonProtocol.AnalyzeConfig config = new DaemonProtocol.AnalyzeConfig(
                projectDir.toAbsolutePath().toString(),
                System.getProperty("java.home"),
                "UTF-8",
                List.of("java.base"),
                List.of(new DaemonProtocol.SourceRoot("main", "src", false)),
                List.of(),
                List.of(),
                false,
                warnNearMisses);
        return service.analyze(new DaemonProtocol.AnalyzeProject("test", config, cold), status -> { });
    }

    /** The display annotations computed for the first element of {@code kind} whose fqn contains {@code fqnPart}. */
    public static List<String> displayFor(DaemonProtocol.Result result, String kind, String fqnPart) {
        return result.elementAnnotations().stream()
                .filter(e -> kind.equals(e.kind()) && e.fqn() != null && e.fqn().contains(fqnPart))
                .flatMap(e -> e.displayAnnotations().stream())
                .toList();
    }

    /** The first finding of the given category, if any. */
    public static Optional<DaemonProtocol.Finding> finding(DaemonProtocol.Result result, String category) {
        return result.findings().stream().filter(f -> category.equals(f.category())).findFirst();
    }

    /** The tagged annotations of every element of {@code kind} whose fqn contains {@code fqnPart}. */
    public static List<DaemonProtocol.Annotation> annotationsFor(DaemonProtocol.Result result, String kind,
                                                                 String fqnPart) {
        return result.elementAnnotations().stream()
                .filter(e -> kind.equals(e.kind()) && e.fqn() != null && e.fqn().contains(fqnPart))
                .flatMap(e -> e.annotations().stream())
                .toList();
    }
}
