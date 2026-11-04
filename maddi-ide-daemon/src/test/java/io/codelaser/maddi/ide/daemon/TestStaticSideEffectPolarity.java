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

import static io.codelaser.maddi.ide.daemon.DaemonAnalysisFixture.analyze;
import static io.codelaser.maddi.ide.daemon.DaemonAnalysisFixture.annotationsFor;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The computed {@code @StaticSideEffects} verdict reaches the IDE surfaces end-to-end: the daemon decorates a
 * static-side-effect method with {@code @StaticSideEffects} and tags it with the {@code NEGATIVE} attention
 * polarity (an outward effect the designer should always see, not a missing safety guarantee, but rendered like
 * the baseline cautions). See road-to-immutability §050 "Static side effects".
 */
public class TestStaticSideEffectPolarity {

    // raise() assigns another type's static field -> a static side effect
    private static final String SOURCE = """
            package x;
            class Config { static int level; }
            public class X {
                public void raise() { Config.level = 5; }
                public void plain() { int y = 1 + 1; }
            }
            """;

    private static DaemonProtocol.Annotation one(List<DaemonProtocol.Annotation> anns, String prefix) {
        return anns.stream().filter(a -> a.text().startsWith(prefix)).findFirst().orElseThrow(
                () -> new AssertionError("no annotation starting with '" + prefix + "' in " + anns));
    }

    @DisplayName("a computed @StaticSideEffects verdict is decorated and tagged NEGATIVE, end-to-end")
    @Test
    public void test(@TempDir Path dir) throws Exception {
        // on by default since maddi-mod#65; the explicit switch keeps the test independent of that default
        DaemonProtocol.Result r = analyze(dir, "x/X.java", SOURCE, new WarmAnalysisService(true));
        assertEquals(0, r.parseErrorCount(), "unexpected parse errors");

        List<DaemonProtocol.Annotation> raise = annotationsFor(r, "METHOD", "X.raise");
        DaemonProtocol.Annotation sse = one(raise, "@StaticSideEffects");
        assertEquals(AnnotationTagger.NEGATIVE, sse.polarity(), sse.toString());

        List<DaemonProtocol.Annotation> plain = annotationsFor(r, "METHOD", "X.plain");
        assertTrue(plain.stream().noneMatch(a -> a.text().startsWith("@StaticSideEffects")),
                "a method with no static side effect must not carry @StaticSideEffects: " + plain);
    }
}
