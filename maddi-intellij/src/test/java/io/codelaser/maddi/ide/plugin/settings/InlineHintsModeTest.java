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

package io.codelaser.maddi.ide.plugin.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.codelaser.maddi.ide.client.AnalysisModel;

/** Pure logic: the inline-hints mode decides per annotation what to show. No IntelliJ platform needed. */
public class InlineHintsModeTest {

    private static final AnalysisModel.Annotation POS_CTX =
            new AnalysisModel.Annotation("@NotModified", "POSITIVE", true);   // container's param: implied
    private static final AnalysisModel.Annotation POS =
            new AnalysisModel.Annotation("@Immutable", "POSITIVE", false);    // proven on the type: informative
    private static final AnalysisModel.Annotation NEG =
            new AnalysisModel.Annotation("@Modified", "NEGATIVE", false);     // a modified param: the signal
    private static final AnalysisModel.Annotation NEG_CTX =
            new AnalysisModel.Annotation("@Mutable", "NEGATIVE", true);       // baseline clutter
    private static final AnalysisModel.Annotation NEUTRAL =
            new AnalysisModel.Annotation("@GetSet", "NEUTRAL", false);

    @Test
    public void all() {
        for (AnalysisModel.Annotation a : new AnalysisModel.Annotation[]{POS_CTX, POS, NEG, NEG_CTX, NEUTRAL}) {
            assertTrue(InlineHintsMode.ALL.shows(a));
            assertFalse(InlineHintsMode.NONE.shows(a));
        }
    }

    @Test
    public void positiveOnly() {
        assertTrue(InlineHintsMode.POSITIVE_ONLY.shows(POS));
        assertTrue(InlineHintsMode.POSITIVE_ONLY.shows(POS_CTX));
        assertTrue(InlineHintsMode.POSITIVE_ONLY.shows(NEUTRAL));  // neutrals kept
        assertFalse(InlineHintsMode.POSITIVE_ONLY.shows(NEG));
        assertFalse(InlineHintsMode.POSITIVE_ONLY.shows(NEG_CTX));
    }

    @Test
    public void negativeOnly() {
        assertTrue(InlineHintsMode.NEGATIVE_ONLY.shows(NEG));
        assertTrue(InlineHintsMode.NEGATIVE_ONLY.shows(NEG_CTX));
        assertTrue(InlineHintsMode.NEGATIVE_ONLY.shows(NEUTRAL));  // neutrals kept
        assertFalse(InlineHintsMode.NEGATIVE_ONLY.shows(POS));
        assertFalse(InlineHintsMode.NEGATIVE_ONLY.shows(POS_CTX));
    }

    @Test
    public void hideContextDefaults() {
        assertTrue(InlineHintsMode.HIDE_CONTEXT_DEFAULTS.shows(POS));      // informative positive kept
        assertTrue(InlineHintsMode.HIDE_CONTEXT_DEFAULTS.shows(NEG));      // the @Modified signal kept
        assertTrue(InlineHintsMode.HIDE_CONTEXT_DEFAULTS.shows(NEUTRAL));
        assertFalse(InlineHintsMode.HIDE_CONTEXT_DEFAULTS.shows(POS_CTX)); // implied positive hidden
        assertFalse(InlineHintsMode.HIDE_CONTEXT_DEFAULTS.shows(NEG_CTX)); // baseline negative hidden
    }
}
