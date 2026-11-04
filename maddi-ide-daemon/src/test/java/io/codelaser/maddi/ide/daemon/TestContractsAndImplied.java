package io.codelaser.maddi.ide.daemon;

import static io.codelaser.maddi.ide.daemon.DaemonAnalysisFixture.analyze;
import static io.codelaser.maddi.ide.daemon.DaemonAnalysisFixture.annotationsFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * maddi-dist#6: what the author wrote on an element is not shown again as computed, and the {@code @Independent} a
 * {@code @Fluent} method implies is a context default.
 */
public class TestContractsAndImplied {

    private static final String SOURCE = """
            package x;
            import io.codelaser.maddi.annotation.rare.Finalizer;
            import io.codelaser.maddi.annotation.rare.IgnoreModifications;
            public class R {
                private final StringBuilder sb = new StringBuilder();
                private int total;
                public R line(String s) { sb.append(s); return this; }
                @Finalizer
                public String build() { return sb.toString(); }
                @IgnoreModifications
                public void log(String s) { sb.append(s); }
                public void add(int x) { total += x; }
            }
            """;

    private static List<String> texts(List<DaemonProtocol.Annotation> anns) {
        return anns.stream().map(DaemonProtocol.Annotation::text).toList();
    }

    @DisplayName("contracts are not repeated as computed; @Fluent's @Independent is a context default")
    @Test
    public void test(@TempDir Path dir) throws Exception {
        DaemonProtocol.Result r = analyze(dir, "x/R.java", SOURCE);
        assertEquals(0, r.parseErrorCount(), "unexpected parse errors");

        assertTrue(texts(annotationsFor(r, "METHOD", "R.build")).stream().noneMatch(t -> t.startsWith("@Finalizer")),
                () -> "build: " + annotationsFor(r, "METHOD", "R.build"));
        assertTrue(texts(annotationsFor(r, "METHOD", "R.log")).stream()
                        .noneMatch(t -> t.startsWith("@IgnoreModifications")),
                () -> "log: " + annotationsFor(r, "METHOD", "R.log"));

        List<DaemonProtocol.Annotation> line = annotationsFor(r, "METHOD", "R.line");
        assertTrue(texts(line).contains("@Fluent"), () -> "line: " + line);
        line.stream().filter(a -> a.text().startsWith("@Independent"))
                .forEach(a -> assertTrue(a.contextDefault(), "implied by @Fluent: " + a));
    }
}
