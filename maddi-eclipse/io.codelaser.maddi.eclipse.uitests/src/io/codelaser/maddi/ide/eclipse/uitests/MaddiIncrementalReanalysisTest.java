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

package io.codelaser.maddi.ide.eclipse.uitests;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.codelaser.maddi.ide.eclipse.uitests.Workbench.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Incremental re-analysis (the experimental preference: a warm daemon) across three types of the fixture:
 * <ul>
 *   <li>{@code Cell}, "before": the changed type depends on it, it depends on nothing that changes;</li>
 *   <li>{@code Box}, "changed": the test edits {@code peek(Cell)} so that it modifies its cell;</li>
 *   <li>{@code Shelf}, "rewired": it uses both, so the edit of Box re-points it at the new Box.</li>
 * </ul>
 * The first edit is visible to Shelf: {@code read(Box, Cell)} hands its cell to {@code peek}. The second is not: it
 * aliases the cell, which leaves peek's summary as it was, so the early cutoff holds and Shelf is not analysed again.
 * A cold run at the end must say what the incremental runs said.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MaddiIncrementalReanalysisTest {

    private static final List<String> FILES = List.of("Box.java", "Cell.java", "Shelf.java");
    private static final String PEEK = "public int peek(";
    private static final String CELL = "Cell cell";

    private String boxText;
    private Map<String, List<Hint>> first;
    private Map<String, List<Hint>> incremental;
    private Run incrementalRun;
    private ITextEditor box;

    @BeforeAll
    public void analyzeEditAnalyze() throws Exception {
        demoProject();
        configure(true); // relaunches the daemon, warm
        boxText = new String(file("Box.java").getContents().readAllBytes(), StandardCharsets.UTF_8);
        box = open("Box.java");
        Run cold = analyze(box);
        assertNotEquals("INCREMENTAL", cold.outcome(), "the first run of a warm daemon is cold");
        first = hintsOf(FILES);

        replaceAndSave(box, "return cell.get();", "cell.set(cell.get() + 1); return cell.get();");
        incrementalRun = analyze(box);
        incremental = hintsOf(FILES);
    }

    @AfterAll
    public void restore() throws Exception {
        configure(false);
        ui(() -> {
            box.close(false);
            return null;
        });
        file("Box.java").setContents(new ByteArrayInputStream(boxText.getBytes(StandardCharsets.UTF_8)), true, false,
                new NullProgressMonitor());
    }

    @Test
    @Order(1)
    @DisplayName("re-analyses incrementally, re-parsing only the changed type")
    public void incrementalRun() {
        assertEquals("INCREMENTAL", incrementalRun.outcome(), () -> incrementalRun.toString());
        assertTrue(incrementalRun.steps().contains("parse: re-parsing 1 changed type(s)"),
                () -> "steps: " + incrementalRun.steps());
    }

    @Test
    @Order(2)
    @DisplayName("shows the edit on the changed type")
    public void changedType() {
        String before = parameterHint(box, first.get("Box.java"), PEEK, CELL);
        assertTrue(before.contains("@NotModified"), () -> "before the edit: " + before);
        String after = parameterHint(box, hints(box), PEEK, CELL);
        assertTrue(after.contains("@Modified") && !after.contains("@NotModified"), () -> "after the edit: " + after);
    }

    @Test
    @Order(3)
    @DisplayName("leaves the type before it as it was")
    public void typeBefore() {
        assertEquals(first.get("Cell.java"), incremental.get("Cell.java"));
    }

    @Test
    @Order(4)
    @DisplayName("carries the change into the rewired type that uses both")
    public void rewiredType() {
        ITextEditor shelf = open("Shelf.java");
        List<Hint> hints = hints(shelf);
        String read = parameterHint(shelf, hints, "public int read(", CELL);
        assertTrue(read.contains("@Modified") && !read.contains("@NotModified"),
                () -> "read hands its cell to peek, which now modifies it: " + read);
        String direct = parameterHint(shelf, hints, "public int direct(", CELL);
        assertTrue(direct.contains("@NotModified"), () -> "direct does not go through Box: " + direct);
    }

    @Test
    @Order(5)
    @DisplayName("keeps the rewired type as it was when the edit is invisible to it")
    public void cutoff() {
        // same summary for peek: the cell is modified, and what it returns still comes from the cell
        replaceAndSave(box, "cell.set(cell.get() + 1); return cell.get();",
                "Cell c = cell; c.set(c.get() + 1); return c.get();");
        Run run = analyze(box);
        assertEquals("INCREMENTAL", run.outcome(), () -> run.toString());
        assertTrue(run.steps().contains("parse: re-parsing 1 changed type(s)"), () -> "steps: " + run.steps());
        assertTrue(run.steps().contains("analyze: modification analysis, seeded with 1 type(s)"),
                () -> "steps: " + run.steps());
        assertFalse(run.steps().stream().anyMatch(step -> step.contains("(first)")),
                () -> "the cutoff did not hold: " + run.steps());
        Map<String, List<Hint>> now = hintsOf(FILES);
        assertEquals(incremental.get("Shelf.java"), now.get("Shelf.java"));
        assertEquals(incremental.get("Cell.java"), now.get("Cell.java"));
        String peek = parameterHint(box, now.get("Box.java"), PEEK, CELL);
        assertTrue(peek.contains("@Modified") && !peek.contains("@NotModified"), () -> peek);
        incremental = now;
    }

    @Test
    @Order(6)
    @DisplayName("says what a cold run says")
    public void coldRunAgrees() {
        configure(false); // relaunches the daemon, cold
        Run cold = analyze(box);
        assertNotEquals("INCREMENTAL", cold.outcome());
        assertEquals(incremental, hintsOf(FILES));
    }
}
