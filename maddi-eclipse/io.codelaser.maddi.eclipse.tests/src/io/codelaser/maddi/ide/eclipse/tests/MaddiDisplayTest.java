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

package io.codelaser.maddi.ide.eclipse.tests;

import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.ide.eclipse.HintFilter;
import io.codelaser.maddi.ide.eclipse.MaddiFindingsView;
import io.codelaser.maddi.ide.eclipse.MaddiJdks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the Eclipse surfaces say, measured against the IntelliJ plugin's: the inline-hint filter's labels and its
 * abbreviated eventual rosters, the findings tree with its why-chains and initialization problems, and reading a
 * JDK's release.
 */
public class MaddiDisplayTest {

    @Test
    @DisplayName("the inline-hint choices read as IntelliJ's do, so one description fits both plugins")
    public void filterLabelsMatchIntelliJ() {
        assertEquals(List.of("Hide context defaults", "Show all", "Positive only (safe: @NotModified, @Immutable, …)",
                        "Negative only (@Modified, @Mutable, …)", "Off (gutter only)"),
                java.util.Arrays.stream(HintFilter.values()).map(HintFilter::label).toList());
    }

    @Test
    @DisplayName("an inline hint hides context defaults and abbreviates a long after= roster")
    public void inlineHintText() {
        AnalysisModel.ElementAnnotation element = new AnalysisModel.ElementAnnotation("file:///A.java", 1, 1, 1, 1,
                "TYPE", "A", List.of(), List.of(
                new AnalysisModel.Annotation("@Immutable(hc=true,after=\"a,b,c,d,e\")", "EVENTUAL", false),
                new AnalysisModel.Annotation("@Independent", "POSITIVE", true)), Map.of());
        assertEquals("@Immutable(hc=true,after=5)", HintFilter.HIDE_CONTEXT_DEFAULTS.textFor(element));
        assertEquals("@Immutable(hc=true,after=5) @Independent", HintFilter.ALL.textFor(element));
        assertEquals("", HintFilter.NONE.textFor(element));
    }

    @Test
    @DisplayName("findings are grouped by file with their causes as children; initialization problems follow")
    public void findingsTree() {
        AnalysisModel.Finding cause = new AnalysisModel.Finding("file:///p/A.java", 3, 1, 3, 2, "ERROR", "cause",
                "field f is modified", List.of());
        AnalysisModel.Finding finding = new AnalysisModel.Finding("file:///p/A.java", 2, 1, 2, 2, "ERROR",
                "contract-violation", "A is not @Immutable", List.of(cause));
        AnalysisModel.Finding nearMiss = new AnalysisModel.Finding("file:///p/B.java", 1, 1, 1, 2, "WARN",
                "near-miss-container", "B is one method away", List.of());
        AnalysisModel.Result result = new AnalysisModel.Result("r", List.of(finding, nearMiss), List.of(),
                List.of("2 Kotlin source file(s) are NOT analyzed"), 0, 0, 0, "CERTIFIED");

        List<MaddiFindingsView.Row> rows = MaddiFindingsView.rows(result);

        assertEquals(List.of("A.java", "B.java", "[initialization] 2 Kotlin source file(s) are NOT analyzed"),
                rows.stream().map(MaddiFindingsView.Row::label).toList());
        MaddiFindingsView.Row violation = rows.getFirst().children().getFirst();
        assertEquals("[ERROR/contract-violation] A is not @Immutable", violation.label());
        assertEquals("[ERROR/cause] field f is modified", violation.children().getFirst().label());
        assertEquals(3, violation.children().getFirst().line(), "a cause navigates to its own location");
        assertEquals("INFO", rows.get(1).children().getFirst().severity(), "a near miss is advisory");
    }

    @Test
    @DisplayName("a JDK's release is read from its version string")
    public void jdkRelease() {
        assertEquals(25, MaddiJdks.feature("25.0.1"));
        assertEquals(27, MaddiJdks.feature("27-ea"));
        assertEquals(8, MaddiJdks.feature("1.8.0_402"));
        assertEquals(0, MaddiJdks.feature(null));
        assertEquals(0, MaddiJdks.feature("unknown"));
    }
}
