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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/*
 The IDE's "Translate to Kotlin with maddi": project types translated from the last analysis's model, the verdicts
 deciding what the translator can say (a read-only List for a list nobody modifies, nullability).
 */
public class TranslateTest {

    private static final String SOURCE = """
            package x;
            import java.util.ArrayList;
            import java.util.List;
            public class Names {
                private final List<String> names = new ArrayList<>();
                public void add(String s) { names.add(s); }
                public int count() { return names.size(); }
                public List<String> copy() { return new ArrayList<>(names); }
            }
            """;

    @DisplayName("project types to Kotlin from the last run; unknown types and no model said so")
    @Test
    public void translate(@TempDir Path projectDir) throws Exception {
        WarmAnalysisService service = new WarmAnalysisService();
        DaemonProtocol.Translate request = new DaemonProtocol.Translate("t", List.of("x.Names", "no.Such"), true);
        DaemonProtocol.TranslateResult before = service.translate(request);
        assertTrue(before.files().isEmpty(), "no analysis yet, no model");
        assertEquals(request.types(), before.unknownTypes());

        DaemonAnalysisFixture.analyze(projectDir, "x/Names.java", SOURCE, service);
        DaemonProtocol.TranslateResult result = service.translate(request);
        assertEquals("t", result.requestId());
        assertEquals(List.of("no.Such"), result.unknownTypes());
        assertEquals(List.of(), result.crashes());
        assertEquals(1, result.files().size(), result::toString);
        DaemonProtocol.TranslatedFile file = result.files().getFirst();
        assertEquals("x.Names", file.type());
        assertEquals("x/Names.kt", file.path());
        assertTrue(file.source().contains("class Names"), file.source());
        assertTrue(file.source().contains("fun add(s: String)"), file.source());
        // the analysis says names is modified (add), so the field keeps a mutable list type
        assertTrue(file.source().contains("MutableList<String>") || file.source().contains("ArrayList<String>"),
                file.source());
    }

    @DisplayName("one file on its own, the rest staying Java: the same translation, read-only types only where unseen")
    @Test
    public void onItsOwn(@TempDir Path projectDir) throws Exception {
        WarmAnalysisService service = new WarmAnalysisService();
        DaemonAnalysisFixture.analyze(projectDir, "x/Names.java", SOURCE, service);
        DaemonProtocol.TranslateResult result = service.translate(new DaemonProtocol.Translate("t",
                List.of("x.Names"), false));
        assertEquals(1, result.files().size(), result::toString);
        assertTrue(result.files().getFirst().source().contains("class Names"));
    }
}
