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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A compilation unit that does not parse must not cost the REST of the project its analysis.
 * <p>
 * The daemon used to return findings-only whenever {@code summary.haveErrors()}, which on a real tree meant one
 * unresolvable module yielded zero annotations everywhere — measured on Apache Pulsar (2026-08-21): 344 parse
 * errors over 98 source roots, and not one element annotation. That also contradicted the deliberate
 * {@code setFailFast(false)}, which exists so a mid-edit tree still yields something.
 * <p>
 * ⚠ The broken file here has a SYNTAX error, and that shape is chosen, not arbitrary: an unresolved reference
 * is filed as a parse WARNING by the openjdk inspector (maddi routinely runs on a deliberately partial classpath),
 * so it would leave {@code haveErrors()} false and never reach the code under test. Until 2026-10-01 the fixture
 * extended a type that does not exist, the field shape ({@code CommonMojo extends AbstractMojo}): the implicit
 * {@code super()} then raised a parse ERROR in {@code ScanCompilationUnit}. That is an unresolved symbol too,
 * and is now dropped with a warning, which is the behaviour this daemon wants anyway — so the premise assertion
 * below fired, as it was written to.
 */
public class TestPartialParse {

    // mid-edit: an unclosed parameter list. A syntax error is a parse ERROR, not a partial-classpath warning
    private static final String BROKEN = """
            package p;
            public class Broken {
                int oops( {
            }
            """;

    // ordinary, self-contained, and analysable: no reference to Broken, so its verdicts are unaffected
    private static final String HEALTHY = """
            package p;
            import java.util.List;
            public class Healthy {
                private final List<String> list;
                public Healthy(List<String> list) {
                    this.list = list;
                }
                public int size() {
                    return list.size();
                }
            }
            """;

    @Test
    public void testBrokenUnitDoesNotSilenceTheProject(@TempDir Path projectDir) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p/Broken.java", BROKEN);
        sources.put("p/Healthy.java", HEALTHY);
        DaemonProtocol.Result result = DaemonAnalysisFixture.analyzeAll(projectDir, sources);

        // the premise: without a real parse ERROR this test proves nothing, so assert it rather than assume it
        assertTrue(result.parseErrorCount() > 0,
                "expected the broken unit to produce a parse error; got " + result.parseErrorCount()
                + ". If this fails the fixture no longer reproduces the shape, NOT that the fix regressed.");

        // the point: the healthy unit was still analysed
        assertFalse(result.elementAnnotations().isEmpty(),
                "a parse error silenced the whole project: no element annotations at all");
        assertTrue(result.elementAnnotations().stream()
                        .anyMatch(e -> e.fqn() != null && e.fqn().contains("Healthy")),
                "the healthy type was not analysed; annotated fqns: "
                + result.elementAnnotations().stream().map(DaemonProtocol.ElementAnnotation::fqn).toList());

        // and it is reported as partial: a run that did not see every reference can never be certified
        assertEquals(DaemonProtocol.OUTCOME_UNKNOWN, result.outcome(),
                "a partial parse must not be reported as a certified fixpoint");
    }

    /** Control: the same healthy source alone parses clean, so the assertions above are about the broken unit. */
    @Test
    public void testHealthyAloneParsesClean(@TempDir Path projectDir) throws Exception {
        DaemonProtocol.Result result = DaemonAnalysisFixture.analyzeAll(projectDir,
                Map.of("p/Healthy.java", HEALTHY));
        assertEquals(0, result.parseErrorCount(), "the healthy source is not supposed to produce parse errors");
        assertFalse(result.elementAnnotations().isEmpty());
    }
}
