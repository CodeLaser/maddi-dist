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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain-JSON DTOs mirroring the daemon's {@code DaemonProtocol} payloads, on the plugin side.
 * Kept structurally identical (field names) so Jackson maps them directly; no maddi types cross over.
 */
public final class AnalysisModel {
    private AnalysisModel() {
    }

    // ---- request ----

    public record SourceRoot(String name, String path, boolean test) {
    }

    public record ClasspathEntry(String path, String scope) {
    }

    /** One compiled unit with its own class path and its own named direct dependencies; see DaemonProtocol. */
    public record ModuleSourceSet(String name,
                                  List<String> sourceDirectories,
                                  String outputPath,
                                  boolean test,
                                  int sourceRelease,
                                  List<String> dependencies,
                                  List<String> classPath) {
    }

    public record AnalyzeConfig(String workingDirectory,
                                String sdkHome,
                                String sourceEncoding,
                                List<String> jmods,
                                List<SourceRoot> sources,
                                List<ClasspathEntry> classpath,
                                List<String> restrictToPackages,
                                boolean parallel,
                                boolean warnNearMisses,
                                List<ModuleSourceSet> moduleSourceSets,
                                Integer containerWhyMaxSlots) {

        /**
         * Why a type is not {@code @Container} is sent only when at most this many parameters stand in its way: a
         * type close to a container is worth the explanation, one that modifies everything it is given is not.
         */
        public static final int DEFAULT_CONTAINER_WHY_MAX_SLOTS = 2;

        public AnalyzeConfig(String workingDirectory, String sdkHome, String sourceEncoding, List<String> jmods,
                             List<SourceRoot> sources, List<ClasspathEntry> classpath,
                             List<String> restrictToPackages, boolean parallel, boolean warnNearMisses,
                             List<ModuleSourceSet> moduleSourceSets) {
            this(workingDirectory, sdkHome, sourceEncoding, jmods, sources, classpath, restrictToPackages,
                    parallel, warnNearMisses, moduleSourceSets, null);
        }

        /** This configuration, explaining a non-container with up to {@code max} modified parameters. */
        public AnalyzeConfig withContainerWhyMaxSlots(Integer max) {
            return new AnalyzeConfig(workingDirectory, sdkHome, sourceEncoding, jmods, sources, classpath,
                    restrictToPackages, parallel, warnNearMisses, moduleSourceSets, max);
        }

        /** {@link #containerWhyMaxSlots}, or its default when the front-end did not say. */
        public int containerWhyMaxSlotsOrDefault() {
            return containerWhyMaxSlots == null ? DEFAULT_CONTAINER_WHY_MAX_SLOTS : containerWhyMaxSlots;
        }

        /** The flat form, for front-ends not yet moved over; the daemon then falls back to auto-wiring. */
        public AnalyzeConfig(String workingDirectory, String sdkHome, String sourceEncoding, List<String> jmods,
                             List<SourceRoot> sources, List<ClasspathEntry> classpath,
                             List<String> restrictToPackages, boolean parallel, boolean warnNearMisses) {
            this(workingDirectory, sdkHome, sourceEncoding, jmods, sources, classpath, restrictToPackages,
                    parallel, warnNearMisses, List.of());
        }
    }

    // ---- result ----

    /**
     * A located finding. {@code severity} is only ERROR or WARN; {@code category} is the analyzer's
     * free-form kebab-case discriminator ({@code contract-violation}, {@code near-miss-container},
     * {@code parse}, …) and is what tells apart findings that share a severity — see {@link #isNearMiss}.
     */
    public record Finding(String uri,
                          Integer beginLine, Integer beginCol, Integer endLine, Integer endCol,
                          String severity, String category, String message,
                          List<Finding> causes) {
    }

    /** Category prefix of the analyzer's advisory near-miss warnings ({@code near-miss-container}, …). */
    public static final String NEAR_MISS_PREFIX = "near-miss-";

    /**
     * Is this an advisory near-miss ("one member away from @Container") rather than a defect? These arrive
     * as WARN, like other warnings, so only the category separates them; front-ends render them at their
     * platform's weakest level so a suggestion never looks like a problem.
     */
    public static boolean isNearMiss(Finding finding) {
        return finding != null && finding.category() != null
               && finding.category().startsWith(NEAR_MISS_PREFIX);
    }

    /**
     * One rendered annotation with polarity and context-default-ness.
     * <p>
     * ⚠ Polarity is <b>POSITIVE / NEGATIVE / NEUTRAL / EVENTUAL</b>. The fourth is the eventual family —
     * {@code @NotModified(after="mark")}, {@code @Immutable(hc=true,after="…")} — and it is deliberately
     * neither positive nor negative: a filter that keeps "everything but NEGATIVE" and one that keeps
     * "everything but POSITIVE" must both keep it, because "immutable once marked" is a different axis from
     * "immutable" and hiding it under either heading loses the only interesting part.
     */
    public record Annotation(String text, String polarity, boolean contextDefault) {
    }

    /** Polarity of the eventual family: a verdict that holds only after a mark. */
    public static final String POLARITY_EVENTUAL = "EVENTUAL";

    /** True when this element carries an eventual verdict, so a surface can mark it as such. */
    public static boolean isEventual(ElementAnnotation element) {
        return element != null && element.annotations() != null
               && element.annotations().stream().anyMatch(a -> POLARITY_EVENTUAL.equals(a.polarity()));
    }

    /** Above this many marks the {@code after=} roster is summarised rather than listed. */
    private static final int AFTER_MARKS_INLINE = 3;

    /**
     * An annotation's text, with a long {@code after="…"} roster collapsed to a count.
     * <p>
     * ⛔ <b>THE ROSTER IS A UNION AND IT IS UNBOUNDED.</b> An eventual verdict names every mark it waits on,
     * and on a real hierarchy that list runs away: measured on maddi's own CST, {@code TestParseInstanceOf
     * Pattern} carries 16 marks, and {@code dogfood/expected-eventual-survivors.txt} records {@code Element}
     * standing at {@code @Immutable(hc=true)} with a <b>60-label union</b>. An inline hint is one line beside
     * the declaration, so rendering that verbatim does not inform anyone — it hides the code.
     * <p>
     * What a reader needs inline is that the verdict IS eventual and roughly how far off it is; the roster
     * itself belongs where there is room for it (the gutter tooltip renders {@code displayAnnotations}
     * unabbreviated). A short roster is left alone: {@code after="runtime"} is the useful case, not the noisy
     * one.
     * <p>
     * ⚠ <b>THE FORM IS BARE ({@code after=6}, not {@code after=6 marks}) BECAUSE THE PLATFORM TRUNCATES AT 30
     * CHARACTERS.</b> Measured through the real declarative-inlay pass (MaddiInlayFilterTest):
     * {@code @Immutable(hc=true,after=6 marks)} is 33 characters and renders as
     * {@code @Immutable(hc=true,after=6 mar…} — the count, the one thing worth abbreviating TO, cut in half by
     * the platform's own ellipsis. Note this truncation applies to the unabbreviated text as well, so an
     * un-summarised roster never showed more than its first label or two anyway; what changes here is that the
     * 30 characters carry the count instead.
     */
    public static String abbreviate(String annotationText) {
        return abbreviateDowncast(abbreviateAfter(annotationText));
    }

    private static String abbreviateAfter(String annotationText) {
        if (annotationText == null) return null;
        int at = annotationText.indexOf("after=\"");
        if (at < 0) return annotationText;
        int from = at + "after=\"".length();
        int to = annotationText.indexOf('"', from);
        if (to < 0) return annotationText; // unterminated: leave it exactly as it came
        String roster = annotationText.substring(from, to);
        if (roster.isEmpty()) return annotationText;
        int marks = 1;
        for (int i = 0; i < roster.length(); i++) if (roster.charAt(i) == ',') marks++;
        if (marks <= AFTER_MARKS_INLINE) return annotationText;
        return annotationText.substring(0, at) + "after=" + marks + annotationText.substring(to + 1);
    }

    private static final java.util.regex.Pattern DOWNCAST_TO = java.util.regex.Pattern.compile(
            "downcast\\s*=\\s*true\\s*,\\s*downcastTo\\s*=\\s*\\{([^}]*)}");

    /**
     * {@code @Modified(downcast=true,downcastTo={"a.b.C",...})}, the runtime types through which a parameter is
     * modified, inline as {@code downcast=C} for one type and {@code downcast=n} for more. In full it is past the
     * platform's 30 characters, which cut it to {@code @Modified(downcast=true,downca…}: a second "downcast" key,
     * seemingly. The gutter tooltip keeps the full text.
     */
    private static String abbreviateDowncast(String annotationText) {
        if (annotationText == null) return null;
        java.util.regex.Matcher m = DOWNCAST_TO.matcher(annotationText);
        if (!m.find()) return annotationText;
        List<String> targets = java.util.Arrays.stream(m.group(1).split(","))
                .map(t -> t.strip().replace("\"", "")).filter(t -> !t.isEmpty()).toList();
        if (targets.isEmpty()) return annotationText;
        String to = targets.size() == 1
                ? targets.getFirst().substring(targets.getFirst().lastIndexOf('.') + 1)
                : String.valueOf(targets.size());
        return annotationText.substring(0, m.start()) + "downcast=" + to + annotationText.substring(m.end());
    }

    /**
     * Why an annotation holds: its culprit, the first statement responsible, followed into the callees. Only where
     * the analysis records one (so far {@code @StaticSideEffects}).
     *
     * @param annotation the annotation's simple name, e.g. {@code StaticSideEffects}
     * @param steps      the element's own statement first
     */
    public record Why(String annotation, List<WhyStep> steps, Boolean negative) {
        public Why(String annotation, List<WhyStep> steps) {
            this(annotation, steps, null);
        }

        /** Why the element is NOT {@code @annotation} (not {@code @Container}): the culprit of a verdict not shown. */
        public boolean negates() {
            return Boolean.TRUE.equals(negative);
        }
    }

    /** One statement of a {@link Why}: where it is, and what it does. Line and column are 1-based. */
    public record WhyStep(String uri, int line, int column, String text) {
    }

    /**
     * Where a why's step is, as an IDE says it beside the element: "line n" in the element's own file, "File.java:n"
     * in another, and "" for a step without a place (a contract, an annotation).
     */
    public static String whereOf(String elementUri, WhyStep step) {
        if (step.line() <= 0) return "";
        if (step.uri() == null || step.uri().equals(elementUri)) return "line " + step.line();
        return step.uri().substring(step.uri().lastIndexOf('/') + 1) + ":" + step.line();
    }

    /**
     * The parameters of a method element that have annotations to show, in declaration order, from the same result.
     * Language-neutral: a parameter's fqn is its method's, then {@code :index:name}.
     */
    public static List<ElementAnnotation> parametersOf(List<ElementAnnotation> elements, ElementAnnotation method) {
        if (!"METHOD".equals(method.kind()) || method.fqn() == null || elements == null) return List.of();
        String prefix = method.fqn() + ":";
        return elements.stream()
                .filter(a -> "PARAMETER".equals(a.kind()) && a.fqn() != null && a.fqn().startsWith(prefix)
                             && a.fqn().indexOf(':', prefix.length()) > prefix.length()
                             && a.displayAnnotations() != null && !a.displayAnnotations().isEmpty())
                .sorted(java.util.Comparator.comparingInt(a -> parameterIndex(a.fqn(), prefix.length())))
                .toList();
    }

    private static int parameterIndex(String parameterFqn, int from) {
        try {
            return Integer.parseInt(parameterFqn.substring(from, parameterFqn.indexOf(':', from)));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * {@code owner} and {@code signature}: the element named as a {@link LibraryElement} is, so that it can be placed
     * on its declaration after edits moved it; {@code null} from a daemon before 0.9.6 (see {@code DaemonProtocol}).
     */
    public record ElementAnnotation(String uri,
                                    Integer beginLine, Integer beginCol, Integer endLine, Integer endCol,
                                    String kind,
                                    String fqn,
                                    List<String> displayAnnotations,
                                    List<Annotation> annotations,
                                    Map<String, String> properties,
                                    List<Why> whys,
                                    String owner,
                                    String signature) {
        /** Without the name-based key. */
        public ElementAnnotation(String uri, Integer beginLine, Integer beginCol, Integer endLine, Integer endCol,
                                 String kind, String fqn, List<String> displayAnnotations, List<Annotation> annotations,
                                 Map<String, String> properties, List<Why> whys) {
            this(uri, beginLine, beginCol, endLine, endCol, kind, fqn, displayAnnotations, annotations, properties,
                    whys, null, null);
        }

        /** Without whys. */
        public ElementAnnotation(String uri, Integer beginLine, Integer beginCol, Integer endLine, Integer endCol,
                                 String kind, String fqn, List<String> displayAnnotations, List<Annotation> annotations,
                                 Map<String, String> properties) {
            this(uri, beginLine, beginCol, endLine, endCol, kind, fqn, displayAnnotations, annotations, properties,
                    List.of());
        }

        /** The same element at another position: where its declaration is now. */
        public ElementAnnotation at(int beginLine, int beginCol, int endLine, int endCol) {
            return new ElementAnnotation(uri, beginLine, beginCol, endLine, endCol, kind, fqn, displayAnnotations,
                    annotations, properties, whys, owner, signature);
        }

        /** Never null: an older daemon's frame has no whys. */
        @Override
        public List<Why> whys() {
            return whys == null ? List.of() : whys;
        }
    }

    public record Result(String requestId,
                         List<Finding> findings,
                         List<ElementAnnotation> elementAnnotations,
                         List<String> initializationProblems,
                         int parseErrorCount,
                         int hintsLoaded,
                         long elapsedMillis,
                         String outcome) {
    }

    /**
     * How settled the values on screen are — the rung of the ladder a front-end should tell the user about.
     * <p>
     * The distinction is not cosmetic. Values refine monotonically, so nothing shown is ever wrong, but a
     * value can still <em>strengthen</em>: positive type-immutability in particular typically only resolves
     * in the analyzer's last (cycle-breaking) pass. So a partially-streamed view systematically understates
     * {@code @Immutable} on types, and a run that stopped at the iteration cap may understate anything —
     * neither of which is visible from the annotations themselves.
     */
    public enum Certainty {
        /** Streamed mid-run: established, but the analysis may still strengthen it. */
        PROVISIONAL,
        /** The fixpoint was certified: these values are final. */
        FINAL,
        /** The run stopped at the iteration cap or on a plateau: best available, not certified. */
        BEST_AVAILABLE,
        /** No fixpoint ran at all (e.g. the parse failed), so there is nothing to be certain about. */
        UNKNOWN,
    }

    public static final String OUTCOME_CERTIFIED = "CERTIFIED";
    public static final String OUTCOME_UNKNOWN = "UNKNOWN";

    /**
     * The certainty of a completed result. {@code null}/absent outcome is treated as UNKNOWN rather than
     * assumed final: an older daemon that does not send the field must not have its values presented as
     * certified.
     */
    public static Certainty certaintyOf(Result result) {
        if (result == null || result.outcome() == null || OUTCOME_UNKNOWN.equals(result.outcome())) {
            return Certainty.UNKNOWN;
        }
        // A partial parse can never be final, whatever the fixpoint said: the compilation units that failed
        // contribute no references, so a surviving type's properties may be weaker than its code allows.
        if (result.parseErrorCount() > 0) return Certainty.BEST_AVAILABLE;
        return OUTCOME_CERTIFIED.equals(result.outcome()) ? Certainty.FINAL : Certainty.BEST_AVAILABLE;
    }

    /** True when some compilation units did not parse, so the analysis ran on less than the whole project. */
    public static boolean isPartialParse(Result result) {
        return result != null && result.parseErrorCount() > 0;
    }

    /** A short phrase for a status line or tooltip; null when there is nothing worth saying. */
    public static String certaintyLabel(Certainty certainty) {
        return switch (certainty) {
            case PROVISIONAL -> "provisional — the analysis is still running";
            case FINAL -> null; // the normal case; saying so on every hint would be noise
            case BEST_AVAILABLE -> "best available — the analysis did not reach a fixpoint";
            case UNKNOWN -> "no completed analysis";
        };
    }

    /**
     * Analysis values established after one pass, streamed before the run finishes so the editor can be
     * annotated early. Arrives as a non-terminal {@code partialResult} frame, i.e. through
     * {@code DaemonClient.analyze}'s status consumer — see {@link #PARTIAL_RESULT}.
     * <p>
     * Merge these by element rather than replacing wholesale: {@code elements} is what one pass analyzed
     * (everything on the first, a shrinking subset after), not a complete picture. Nothing here is ever
     * retracted — values are write-once and only strengthen — so a merge never has to remove an annotation
     * a previous frame provided. {@code certain} means the fixpoint was certified and the values are final.
     */
    public record PartialResult(String requestId,
                                int iteration,
                                boolean fullPass,
                                boolean certain,
                                List<ElementAnnotation> elements) {
    }

    /** Frame type of a streamed {@link PartialResult}, as it appears in the status consumer. */
    public static final String PARTIAL_RESULT = "partialResult";

    /**
     * Fold a streamed pass into what is on screen, giving the result a front-end should now display.
     * <p>
     * Merges by element rather than replacing: a frame carries the elements of one pass, so replacing would
     * make everything the previous passes established disappear. An element is identified by {@code kind} +
     * {@code fqn} — its position can move as the user types, its identity cannot. Existing entries are
     * updated in place so the display order stays stable across frames, and unseen elements are kept:
     * values are write-once and only strengthen, so a pass that did not mention an element says nothing
     * against it.
     * <p>
     * Findings are carried over untouched; partial frames never contain any (guard findings are computed
     * after the fixpoint).
     *
     * @param current the result being displayed, or null before the first frame
     * @return a new result; neither argument is modified
     */
    public static Result merge(Result current, PartialResult partial) {
        Map<String, ElementAnnotation> byIdentity = new LinkedHashMap<>();
        if (current != null && current.elementAnnotations() != null) {
            for (ElementAnnotation e : current.elementAnnotations()) byIdentity.put(identity(e), e);
        }
        if (partial.elements() != null) {
            for (ElementAnnotation e : partial.elements()) byIdentity.put(identity(e), e);
        }
        List<ElementAnnotation> merged = List.copyOf(byIdentity.values());
        if (current == null) {
            // a run in progress has no outcome yet: UNKNOWN, so certaintyOf never calls it final
            return new Result(partial.requestId(), List.of(), merged, List.of(), 0, 0, 0L, OUTCOME_UNKNOWN);
        }
        return new Result(current.requestId(), current.findings(), merged,
                current.initializationProblems(), current.parseErrorCount(), current.hintsLoaded(),
                current.elapsedMillis(), current.outcome());
    }

    private static String identity(ElementAnnotation e) {
        return e.kind() + " " + e.fqn();
    }

    // ---- library elements (the daemon's libraryResult) ----

    /** Frame types of the library request and its answer. */
    public static final String LIBRARY_ANNOTATIONS = "libraryAnnotations";
    public static final String LIBRARY_RESULT = "libraryResult";

    /** Where a library element's values come from: see the daemon's {@code DaemonProtocol.LibraryElement}. */
    public static final String ORIGIN_ANNOTATED = "annotated";
    public static final String ORIGIN_DEFAULT = "default";
    public static final String ORIGIN_COMPUTED = "computed";

    /**
     * One element of a library type, without a position: matched to the declaration on screen by {@code type} (the
     * declaring type), {@code kind} and {@code signature} (a method's name and erased parameter types, a field's
     * name, a parameter's method signature with {@code #index}, a type's fully qualified name).
     */
    public record LibraryElement(String kind, String type, String fqn, String signature, String origin,
                                 List<String> assumed, List<String> displayAnnotations, List<Annotation> annotations,
                                 Map<String, String> properties) {
    }

    public record LibraryResult(String requestId, List<LibraryElement> elements, List<String> unknownTypes) {
    }

    // ---- a compile log's configuration (the daemon's inputConfigurationWritten) ----

    public static final String WRITE_INPUT_CONFIGURATION = "writeInputConfiguration";
    public static final String INPUT_CONFIGURATION_WRITTEN = "inputConfigurationWritten";

    // ---- translation (the daemon's translateResult) ----

    public static final String TRANSLATE = "translate";
    public static final String TRANSLATE_RESULT = "translateResult";

    /** One Kotlin file: {@code path} relative to a source root ({@code a/b/Foo.kt}), for the Java type {@code type}. */
    public record TranslatedFile(String type, String path, String source) {
    }

    /** A translator's message at a line of the Java type: severity INFO, BEHAVIOUR_CHANGE, LOSS or ERROR. */
    public record TranslationMessage(String severity, String code, String type, int line, int position,
                                     String detail) {
    }

    public record TranslateResult(String requestId, List<TranslatedFile> files, List<TranslationMessage> messages,
                                  List<String> crashes, List<String> unknownTypes) {
    }

    /** The key, among a placed library element's properties, of {@link #libraryNote}'s text. */
    public static final String LIBRARY_NOTE = "maddi.libraryNote";

    /** What to tell the reader about where a library element's values come from. */
    public static String libraryNote(LibraryElement element) {
        if (ORIGIN_ANNOTATED.equals(element.origin())) return "Manually annotated, in maddi's annotated API";
        if (ORIGIN_DEFAULT.equals(element.origin())) {
            String which = element.assumed() == null ? "" : element.assumed().stream()
                    .map(AnalysisModel::annotationOfAssumed).distinct()
                    .collect(java.util.stream.Collectors.joining(", "));
            return "No annotation in maddi's annotated API" + (which.isEmpty() ? "" : ": " + which + " is the default");
        }
        return "Not in maddi's annotated API: the analysis's defaults for a library";
    }

    private static String annotationOfAssumed(String propertyKey) {
        return switch (propertyKey) {
            case "nonModifyingMethod", "unmodifiedParameter" -> "@Modified";
            default -> propertyKey;
        };
    }

    /**
     * A library element placed on the declaration it matched, as the editor's surfaces take an element of the
     * project's: the note on where its values come from rides along among the properties ({@link #LIBRARY_NOTE}).
     */
    public static ElementAnnotation place(LibraryElement element, String uri, int beginLine, int beginCol,
                                          int endLine, int endCol) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (element.properties() != null) properties.putAll(element.properties());
        properties.put(LIBRARY_NOTE, libraryNote(element));
        return new ElementAnnotation(uri, beginLine, beginCol, endLine, endCol, element.kind(), element.fqn(),
                element.displayAnnotations(), element.annotations(), properties, List.of());
    }
}
