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

import io.codelaser.maddi.analysis.api.AnalysisEngine;
import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.Message;
import io.codelaser.maddi.cst.api.element.Source;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.inspection.api.parser.Summary;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the two maddi output channels — {@code List<Message>} (findings) and the computed properties
 * on each CST {@code Info} (element annotations) — into the plain-JSON {@link DaemonProtocol.Result}
 * shape. No maddi types leak: everything becomes strings/ints.
 * <p>
 * Findings follow {@code ErrorReport.location(Message)}; element annotations come from
 * {@link AnnotationTagger} (the full, polarity/context-tagged set).
 */
public class ResultCollector {
    /** Defensive bound on the recursive why-chain depth. */
    private static final int MAX_CAUSE_DEPTH = 12;

    private final AnnotationTagger tagger;
    private final java.util.Set<Property> bookkeeping;

    public ResultCollector(AnalysisEngine engine, Runtime runtime, SourceSet sourceSetOfRequest) {
        this.tagger = new AnnotationTagger(engine, runtime, sourceSetOfRequest);
        this.bookkeeping = engine.bookkeepingProperties();
    }

    // ---- findings ----

    public List<DaemonProtocol.Finding> collectFindings(List<Message> messages, Summary summary) {
        List<DaemonProtocol.Finding> findings = new ArrayList<>();
        if (messages != null) {
            for (Message m : messages) findings.add(toFinding(m, MAX_CAUSE_DEPTH));
        }
        findings.addAll(parseFindings(summary));
        return findings;
    }

    /** Parse errors/warnings only — used when the project has parse errors and {@code parseResult()} is unsafe. */
    public List<DaemonProtocol.Finding> parseFindings(Summary summary) {
        List<DaemonProtocol.Finding> findings = new ArrayList<>();
        if (summary == null) return findings;
        for (Summary.ParseException pe : summary.parseExceptions()) findings.add(toFinding(pe));
        for (Summary.ParseException pe : summary.parseWarnings()) findings.add(toFinding(pe));
        return findings;
    }

    private DaemonProtocol.Finding toFinding(Message m, int depth) {
        Info info = m.info();
        String uri = uriOf(info);
        Source source = m.source();
        String severity = m.level() != null && m.level().isError() ? "ERROR" : "WARN";
        List<DaemonProtocol.Finding> causes = new ArrayList<>();
        if (depth > 0 && m.causes() != null) {
            for (Message cause : m.causes()) causes.add(toFinding(cause, depth - 1));
        }
        return finding(uri, source, severity, m.category(), m.message(), causes);
    }

    private DaemonProtocol.Finding toFinding(Summary.ParseException pe) {
        String uri = pe.where() instanceof Info info ? uriOf(info) : null;
        String severity = pe.level() != null && pe.level().isError() ? "ERROR" : "WARN";
        return finding(uri, pe.source(), severity, "parse", pe.getMessage(), List.of());
    }

    private static DaemonProtocol.Finding finding(String uri, Source source, String severity,
                                                  String category, String message,
                                                  List<DaemonProtocol.Finding> causes) {
        Integer bl = null, bc = null, el = null, ec = null;
        if (source != null && !source.isNoSource()) {
            bl = source.beginLine();
            bc = source.beginPos();
            el = source.endLine();
            ec = source.endPos();
        }
        return new DaemonProtocol.Finding(uri, bl, bc, el, ec, severity, category, message, causes);
    }

    // ---- element annotations ----

    public List<DaemonProtocol.ElementAnnotation> collectElementAnnotations(Collection<TypeInfo> primaryTypes) {
        List<DaemonProtocol.ElementAnnotation> out = new ArrayList<>();
        for (TypeInfo primaryType : primaryTypes) {
            primaryType.recursiveSubTypeStream().forEach(type -> {
                addElement(out, type);
                type.fields().forEach(field -> addElement(out, field));
                type.constructorAndMethodStream().forEach(method -> {
                    addElement(out, method);
                    for (var parameter : method.parameters()) addElement(out, parameter);
                });
            });
        }
        return out;
    }

    /**
     * Annotations for exactly the elements given, as delivered by {@code AnalysisValueFeed} — which hands
     * over the elements analyzed in a pass, not primary types. A method brings its parameters along: their
     * values are computed with the method but they are separate {@code Info}s, and the feed does not list
     * them.
     * <p>
     * Must be called while the analysis workers are quiescent (the feed's pass boundary): it reads
     * {@code info.analysis()} live.
     */
    public List<DaemonProtocol.ElementAnnotation> collectForElements(Collection<Info> infos) {
        List<DaemonProtocol.ElementAnnotation> out = new ArrayList<>();
        for (Info info : infos) {
            addElement(out, info);
            if (info instanceof MethodInfo method) {
                for (var parameter : method.parameters()) addElement(out, parameter);
            }
        }
        return out;
    }

    private void addElement(List<DaemonProtocol.ElementAnnotation> out, Info info) {
        if (info.isSynthetic()) return;
        // A synthetic method's parameters are not synthetic themselves, and a Kotlin one borrows its source from
        // the declaration it was made from: data class Pair(val b: List<String>) gives copy()'s parameter b the
        // range of the constructor's, with its OWN verdict. Both then compete for one name in the editor, and
        // which one shows was a matter of list order.
        if (info instanceof ParameterInfo parameter && parameter.methodInfo().isSynthetic()) return;
        Source source = info.source();
        if (source == null || source.isNoSource()) return;
        String uri = uriOf(info);
        if (uri == null) return;

        List<DaemonProtocol.Annotation> annotations = tagger.tag(info);
        List<String> displayAnnotations = annotations.stream().map(DaemonProtocol.Annotation::text).toList();

        Map<String, String> properties = new LinkedHashMap<>();
        info.analysis().propertyValueStream()
                // the engine's bookkeeping properties (prep's PREPPED) are per-run state, not a result: showing it would put a property on
                // every prepped type and defeat the "nothing to show → skip" test just below
                .filter(pv -> !bookkeeping.contains(pv.property()) && !CULPRITS.containsKey(pv.property()))
                .forEach(pv -> properties.put(pv.property().key(), String.valueOf(pv.value())));
        List<DaemonProtocol.Why> whys = whys(info, displayAnnotations);

        // nothing computed and nothing to show → skip, to keep the payload lean
        if (displayAnnotations.isEmpty() && properties.isEmpty()) return;

        String[] key = key(info);
        out.add(new DaemonProtocol.ElementAnnotation(
                uri, source.beginLine(), source.beginPos(), source.endLine(), source.endPos(),
                kindOf(info), info.fullyQualifiedName(), displayAnnotations, annotations, properties, whys,
                key == null ? null : key[0], key == null ? null : key[1]));
    }

    /*
     The element's declaring type and its signature there, as collectLibrary names a library's: how an editor finds the
     declaration again once an edit has moved the lines the positions point at. Null for what has no such name.
     */
    private static String[] key(Info info) {
        return switch (info) {
            case TypeInfo type -> new String[]{type.fullyQualifiedName(), type.fullyQualifiedName()};
            case FieldInfo field -> new String[]{field.owner().fullyQualifiedName(), field.name()};
            case MethodInfo method -> new String[]{method.typeInfo().fullyQualifiedName(), signature(method)};
            case ParameterInfo parameter -> new String[]{parameter.methodInfo().typeInfo().fullyQualifiedName(),
                    signature(parameter.methodInfo()) + "#" + parameter.index()};
            default -> null;
        };
    }

    // ---- library elements ----

    /** A library type's own elements and its nested types', with where their values come from; no positions. */
    public List<DaemonProtocol.LibraryElement> collectLibrary(TypeInfo libraryType) {
        List<DaemonProtocol.LibraryElement> out = new ArrayList<>();
        libraryType.recursiveSubTypeStream().forEach(type -> {
            String owner = type.fullyQualifiedName();
            addLibraryElement(out, type, owner, owner);
            type.fields().forEach(field -> addLibraryElement(out, field, owner, field.name()));
            type.constructorAndMethodStream().forEach(method -> {
                String signature = signature(method);
                addLibraryElement(out, method, owner, signature);
                for (ParameterInfo parameter : method.parameters()) {
                    addLibraryElement(out, parameter, owner, signature + "#" + parameter.index());
                }
            });
        });
        return out;
    }

    private void addLibraryElement(List<DaemonProtocol.LibraryElement> out, Info info, String owner,
                                   String signature) {
        if (info.isSynthetic()) return;
        List<DaemonProtocol.Annotation> annotations = tagger.tag(info);
        if (annotations.isEmpty()) return;
        Map<String, String> properties = new LinkedHashMap<>();
        info.analysis().propertyValueStream()
                .filter(pv -> !bookkeeping.contains(pv.property()) && !CULPRITS.containsKey(pv.property()))
                .forEach(pv -> properties.put(pv.property().key(), String.valueOf(pv.value())));
        List<String> assumed = List.copyOf(info.analysis()
                .getOrDefault(PropertyImpl.ASSUMED_DEFAULTS, ValueImpl.SetOfStringsImpl.EMPTY_SET).set());
        String origin = !assumed.isEmpty() ? DaemonProtocol.ORIGIN_DEFAULT
                : info.analysis().getOrDefault(PropertyImpl.ANNOTATED_API, ValueImpl.BoolImpl.FALSE).isTrue()
                ? DaemonProtocol.ORIGIN_ANNOTATED : DaemonProtocol.ORIGIN_COMPUTED;
        out.add(new DaemonProtocol.LibraryElement(kindOf(info), owner, info.fullyQualifiedName(), signature, origin,
                assumed,
                annotations.stream().map(DaemonProtocol.Annotation::text).toList(), annotations, properties));
    }

    /*
     A method's name and its parameters' erasures, the leftmost bound for a type variable, as the JVM erases them: what
     the editor computes from the declaration on screen. Nested types with dots, as a canonical name.
     */
    static String signature(MethodInfo method) {
        return (method.isConstructor() ? "<init>" : method.name()) + method.parameters().stream()
                .map(p -> {
                    ParameterizedType erased = p.parameterizedType().erasedForFQN();
                    TypeInfo typeInfo = erased.bestTypeInfo();
                    String name = typeInfo == null ? "java.lang.Object" : typeInfo.fullyQualifiedName();
                    return name + "[]".repeat(erased.arrays());
                })
                .collect(java.util.stream.Collectors.joining(",", "(", ")"));
    }

    /*
     The culprit properties, and the annotation each explains. The analysis writes them only when the run asks for
     culprits, as the daemon's does (WarmAnalysisService); more will join as other analyses record theirs.
     */
    private static final Map<Property, String> CULPRITS = Map.of(
            PropertyImpl.STATIC_SIDE_EFFECTS_CULPRIT_METHOD, "StaticSideEffects",
            PropertyImpl.MODIFIED_CULPRIT, "Modified",
            PropertyImpl.DEPENDENT_CULPRIT, "Dependent",
            PropertyImpl.NULLABLE_CULPRIT, "Nullable");

    /*
     Only beside its annotation, as shown: a culprit can outlive its verdict (a later MODREACH round leaves an element
     unmodified that an earlier one reached), and an annotation can be hidden by the tagger.
     */
    private static List<DaemonProtocol.Why> whys(Info info, List<String> displayAnnotations) {
        List<DaemonProtocol.Why> whys = new java.util.ArrayList<>();
        CULPRITS.forEach((property, annotation) -> {
            ValueImpl.CulpritImpl culprit = info.analysis().getOrNull(property, ValueImpl.CulpritImpl.class);
            boolean shown = displayAnnotations.stream()
                    .anyMatch(a -> a.equals("@" + annotation) || a.startsWith("@" + annotation + "("));
            if (shown && culprit != null && !culprit.steps().isEmpty()) {
                whys.add(new DaemonProtocol.Why(annotation, culprit.steps().stream()
                        .map(s -> new DaemonProtocol.WhyStep(s.uri(), s.line(), s.column(), s.text())).toList()));
            }
        });
        return whys;
    }

    private static String kindOf(Info info) {
        if (info instanceof TypeInfo) return "TYPE";
        if (info instanceof MethodInfo) return "METHOD";
        if (info instanceof FieldInfo) return "FIELD";
        if (info instanceof ParameterInfo) return "PARAMETER";
        return "OTHER";
    }

    private static String uriOf(Info info) {
        if (info == null || info.compilationUnit() == null) return null;
        URI uri = info.compilationUnit().uri();
        return uri == null ? null : uri.toString();
    }
}
