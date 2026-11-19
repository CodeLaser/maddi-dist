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

package io.codelaser.maddi.ide.plugin.uitest;

import java.util.List;
import java.util.Map;

import io.codelaser.maddi.ide.plugin.settings.InlineHintsMode;
import io.codelaser.maddi.ide.plugin.settings.MaddiSettings;

/**
 * Incremental re-analysis (the experimental setting: a warm daemon) across three types of the fixture, as in the VS
 * Code and Eclipse UI tests:
 * <ul>
 *   <li>{@code Cell}, "before": the changed type depends on it, it depends on nothing that changes;</li>
 *   <li>{@code Box}, "changed": the test edits {@code peek(Cell)} so that it modifies its cell;</li>
 *   <li>{@code Shelf}, "rewired": it uses both, so the edit of Box re-points it at the new Box.</li>
 * </ul>
 * The first edit is visible to Shelf; the second, which aliases the cell, is not: peek's summary stays as it was, so
 * the early cutoff holds. A cold run at the end must say what the incremental runs said. One test method, in order:
 * each step builds on the one before.
 */
public class MaddiIncrementalReanalysisTest extends MaddiIdeTestCase {

    private static final List<String> FILES = List.of("Box.java", "Cell.java", "Shelf.java");
    private static final String PEEK = "public int peek(";
    private static final String CELL = "Cell cell";

    public void testIncrementalReanalysisOfAChangedABeforeAndARewiredType() {
        MaddiSettings.State settings = MaddiSettings.getInstance().getState();
        settings.incrementalReanalysis = true; // a warm daemon
        // "negative only": a parameter shows @Modified when it is modified, nothing when it is not. Not "show all":
        // the platform cuts an inline hint at 30 characters, and a parameter's full set (@Borrowed @Independent
        // @NotModified @NotNull) loses its verdict on modification to the ellipsis. Nor the default, which hides
        // @NotModified in a type that implies it.
        settings.inlineHintsMode = InlineHintsMode.NEGATIVE_ONLY;
        Run cold = analyze("Box.java");
        assertFalse("the first run of a warm daemon is cold", "INCREMENTAL".equals(cold.outcome()));
        Map<String, List<Hint>> first = hintsOf(FILES);
        String peekBefore = parameterHint("Box.java", first.get("Box.java"), PEEK, CELL);
        assertFalse("before the edit: " + peekBefore + " in " + first, peekBefore.contains("@Modified"));

        // the changed type: peek now modifies its cell
        replaceAndSave("Box.java", "return cell.get();", "cell.set(cell.get() + 1); return cell.get();");
        Run incremental = analyze("Box.java");
        assertEquals(incremental.toString(), "INCREMENTAL", incremental.outcome());
        assertTrue("steps: " + incremental.steps(), incremental.steps().contains("parse: re-parsing 1 changed type(s)"));
        Map<String, List<Hint>> second = hintsOf(FILES);
        String peek = parameterHint("Box.java", second.get("Box.java"), PEEK, CELL);
        assertTrue("after the edit: " + peek, peek.contains("@Modified"));

        // the type before it, as it was
        assertEquals(first.get("Cell.java"), second.get("Cell.java"));

        // the rewired type that uses both: read hands its cell to peek, direct does not go through Box
        String read = parameterHint("Shelf.java", second.get("Shelf.java"), "public int read(", CELL);
        assertTrue("read: " + read, read.contains("@Modified"));
        String direct = parameterHint("Shelf.java", second.get("Shelf.java"), "public int direct(", CELL);
        assertFalse("direct: " + direct, direct.contains("@Modified"));

        // an edit the rewired type cannot see: the same summary for peek, so the cutoff holds
        replaceAndSave("Box.java", "cell.set(cell.get() + 1); return cell.get();",
                "Cell c = cell; c.set(c.get() + 1); return c.get();");
        Run invisible = analyze("Box.java");
        assertEquals(invisible.toString(), "INCREMENTAL", invisible.outcome());
        assertTrue("steps: " + invisible.steps(), invisible.steps().contains("parse: re-parsing 1 changed type(s)"));
        assertTrue("steps: " + invisible.steps(),
                invisible.steps().contains("analyze: modification analysis, seeded with 1 type(s)"));
        assertFalse("the cutoff did not hold: " + invisible.steps(),
                invisible.steps().stream().anyMatch(step -> step.contains("(first)")));
        Map<String, List<Hint>> third = hintsOf(FILES);
        assertEquals(second.get("Shelf.java"), third.get("Shelf.java"));
        assertEquals(second.get("Cell.java"), third.get("Cell.java"));
        String peekAliased = parameterHint("Box.java", third.get("Box.java"), PEEK, CELL);
        assertTrue(peekAliased, peekAliased.contains("@Modified"));

        // a cold run says what the incremental runs said
        settings.incrementalReanalysis = false; // relaunches the daemon, cold
        Run coldAgain = analyze("Box.java");
        assertFalse("INCREMENTAL".equals(coldAgain.outcome()));
        assertEquals(third, hintsOf(FILES));
    }
}
