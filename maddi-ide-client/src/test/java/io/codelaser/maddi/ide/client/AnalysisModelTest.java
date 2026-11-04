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

package io.codelaser.maddi.ide.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AnalysisModel#isNearMiss} is the one definition both front-ends use to decide whether a finding is
 * a defect or a suggestion, and it drives how they highlight it. The categories it matches come from the
 * analyzer's {@code GuardAnalyzerImpl}.
 */
public class AnalysisModelTest {

    private static AnalysisModel.Finding withCategory(String category) {
        return new AnalysisModel.Finding("file:///X.java", 1, 1, 1, 2, "WARN", category, "m", List.of());
    }

    @DisplayName("every near-miss category the analyzer emits is recognised")
    @Test
    public void nearMissCategoriesAreRecognised() {
        for (String category : List.of("near-miss-container", "near-miss-not-modified",
                "near-miss-independent", "near-miss-immutable")) {
            assertTrue(AnalysisModel.isNearMiss(withCategory(category)), category);
        }
    }

    @DisplayName("defects and parse errors are not near misses")
    @Test
    public void otherCategoriesAreNot() {
        assertFalse(AnalysisModel.isNearMiss(withCategory("contract-violation")));
        assertFalse(AnalysisModel.isNearMiss(withCategory("parse")));
        assertFalse(AnalysisModel.isNearMiss(withCategory("general")));
    }

    @DisplayName("a missing category never trips the check")
    @Test
    public void nullsAreSafe() {
        assertFalse(AnalysisModel.isNearMiss(withCategory(null)));
        assertFalse(AnalysisModel.isNearMiss(null));
    }

    // ---- merging streamed passes ----

    private static AnalysisModel.ElementAnnotation element(String kind, String fqn, String... shown) {
        return new AnalysisModel.ElementAnnotation("file:///X.java", 1, 1, 1, 2, kind, fqn,
                List.of(shown), List.of(), Map.of());
    }

    private static AnalysisModel.PartialResult pass(int iteration, AnalysisModel.ElementAnnotation... elements) {
        return new AnalysisModel.PartialResult("req", iteration, iteration == 1, false, List.of(elements));
    }

    @DisplayName("the first streamed pass becomes the displayed result")
    @Test
    public void mergeFromNothing() {
        AnalysisModel.Result r = AnalysisModel.merge(null, pass(1, element("TYPE", "x.Box", "@Container")));
        assertEquals(1, r.elementAnnotations().size());
        assertEquals("req", r.requestId());
        assertTrue(r.findings().isEmpty(), "a partial frame never carries findings");
    }

    @DisplayName("a later pass updates the elements it mentions and leaves the rest standing")
    @Test
    public void mergeKeepsUnmentionedElements() {
        AnalysisModel.Result first = AnalysisModel.merge(null, pass(1,
                element("TYPE", "x.Box", "@Container"),
                element("METHOD", "x.Box.get", "@NotModified")));
        // pass 2 is a worklist subset: it says nothing about Box.get, which must not disappear
        AnalysisModel.Result second = AnalysisModel.merge(first, pass(2,
                element("TYPE", "x.Box", "@ImmutableContainer")));

        assertEquals(2, second.elementAnnotations().size(), "the unmentioned method must survive");
        assertEquals(List.of("@ImmutableContainer"), byFqn(second, "x.Box").displayAnnotations(),
                "the type strengthened");
        assertEquals(List.of("@NotModified"), byFqn(second, "x.Box.get").displayAnnotations());
    }

    @DisplayName("elements are identified by kind and name, not position, so edits do not duplicate them")
    @Test
    public void mergeIdentifiesByKindAndName() {
        AnalysisModel.Result first = AnalysisModel.merge(null, pass(1, element("TYPE", "x.Box", "@Container")));
        // same element, moved down the file by an edit
        AnalysisModel.ElementAnnotation moved = new AnalysisModel.ElementAnnotation(
                "file:///X.java", 40, 1, 40, 2, "TYPE", "x.Box", List.of("@Container"), List.of(), Map.of());
        AnalysisModel.Result second = AnalysisModel.merge(first,
                new AnalysisModel.PartialResult("req", 2, false, false, List.of(moved)));

        assertEquals(1, second.elementAnnotations().size(), "must replace, not add a second entry");
        assertEquals(40, second.elementAnnotations().getFirst().beginLine());
    }

    @DisplayName("a method and a type of the same name stay distinct")
    @Test
    public void mergeDoesNotConflateKinds() {
        AnalysisModel.Result r = AnalysisModel.merge(null, pass(1,
                element("TYPE", "x.Box", "@Container"),
                element("METHOD", "x.Box", "@NotModified")));
        assertEquals(2, r.elementAnnotations().size());
    }

    @DisplayName("merging preserves the findings already on screen")
    @Test
    public void mergeKeepsFindings() {
        AnalysisModel.Result withFinding = new AnalysisModel.Result("req", List.of(withCategory("contract-violation")),
                List.of(), List.of(), 0, 7, 123L, AnalysisModel.OUTCOME_CERTIFIED);
        AnalysisModel.Result merged = AnalysisModel.merge(withFinding, pass(2, element("TYPE", "x.Box", "@Container")));
        assertEquals(1, merged.findings().size(), "a partial frame must not drop existing findings");
        assertEquals(7, merged.hintsLoaded(), "nor the rest of the result's context");
    }

    private static AnalysisModel.ElementAnnotation byFqn(AnalysisModel.Result r, String fqn) {
        return r.elementAnnotations().stream().filter(e -> e.fqn().equals(fqn)).findFirst().orElseThrow();
    }

    // ---- the certainty ladder ----

    private static AnalysisModel.Result withOutcome(String outcome) {
        return new AnalysisModel.Result("req", List.of(), List.of(), List.of(), 0, 0, 0L, outcome);
    }

    @DisplayName("a certified run is final; anything else is best-available, not final")
    @Test
    public void certaintyFromOutcome() {
        assertEquals(AnalysisModel.Certainty.FINAL,
                AnalysisModel.certaintyOf(withOutcome(AnalysisModel.OUTCOME_CERTIFIED)));
        assertEquals(AnalysisModel.Certainty.BEST_AVAILABLE,
                AnalysisModel.certaintyOf(withOutcome("MAX_ITERATIONS")));
        assertEquals(AnalysisModel.Certainty.BEST_AVAILABLE,
                AnalysisModel.certaintyOf(withOutcome("PLATEAU")));
    }

    @DisplayName("a missing outcome is never presented as final")
    @Test
    public void unknownOutcomeIsNotFinal() {
        // an older daemon sends no outcome at all; presenting its values as certified would be a lie
        assertEquals(AnalysisModel.Certainty.UNKNOWN, AnalysisModel.certaintyOf(withOutcome(null)));
        assertEquals(AnalysisModel.Certainty.UNKNOWN,
                AnalysisModel.certaintyOf(withOutcome(AnalysisModel.OUTCOME_UNKNOWN)));
        assertEquals(AnalysisModel.Certainty.UNKNOWN, AnalysisModel.certaintyOf(null));
    }

    @DisplayName("a view merged from streamed passes is not final either")
    @Test
    public void mergedStreamIsNotFinal() {
        AnalysisModel.Result streamed = AnalysisModel.merge(null, pass(1, element("TYPE", "x.Box", "@Container")));
        assertEquals(AnalysisModel.Certainty.UNKNOWN, AnalysisModel.certaintyOf(streamed),
                "a run still in flight has no outcome, so its values must not read as certified");
    }

    @DisplayName("only the states worth reporting carry a label")
    @Test
    public void onlyNoteworthyStatesAreLabelled() {
        // saying "final" on every hint of a normal run would be noise; the others change how it should be read
        assertNull(AnalysisModel.certaintyLabel(AnalysisModel.Certainty.FINAL));
        assertNotNull(AnalysisModel.certaintyLabel(AnalysisModel.Certainty.PROVISIONAL));
        assertNotNull(AnalysisModel.certaintyLabel(AnalysisModel.Certainty.BEST_AVAILABLE));
    }
}
