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
import io.codelaser.maddi.cst.api.analysis.PropertyValueMap;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.expression.AnnotationExpression;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.output.Qualification;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders the full set of annotations for one element and tags each with polarity and context-default-ness,
 * so the plugin's inline-hints filter can hide clutter without losing signal.
 * <p>
 * The <em>positive</em> (proven-stronger) and neutral annotations come from the engine's decorator ({@link AnalysisEngine#decorator}) (keeping its
 * exact formatting, e.g. {@code @Immutable(hc=true)}); the <em>negative</em> (baseline) ones — {@code @Modified},
 * {@code @Mutable}, {@code @Dependent}, {@code @Nullable} — which the decorator omits, are computed here from
 * the analysis values. They are mutually exclusive per concept, so nothing is double-rendered: where the
 * decorator does print a {@code @Modified} (the hidden-content-only {@code @Modified(hc=true)}), the baseline one
 * is left out.
 */
public class AnnotationTagger {
    public static final String POSITIVE = "POSITIVE";
    public static final String NEGATIVE = "NEGATIVE";
    public static final String NEUTRAL = "NEUTRAL";
    // an after-mark verdict: proven, but only once the object has passed its mark -- a conditional promise, so
    // neither the plain POSITIVE (proven now) nor the NEGATIVE baseline. Its own polarity so a front-end can
    // style/filter it distinctly; today every front-end shows an unrecognised polarity, so this degrades to
    // "visible" without any client change. See road-to-immutability section 060 and docs/design/eventual-info-hierarchy.md.
    public static final String EVENTUAL = "EVENTUAL";

    // annotation simple names by polarity (the core safety concepts). @UnmodifiableView is a weaker guarantee than
    // @Immutable(hc=true) on the result, but a guarantee all the same: nothing is added or removed through it.
    private static final Set<String> POSITIVE_NAMES = Set.of(
            "NotModified", "Immutable", "ImmutableContainer", "Container", "Independent", "NotNull", "Final",
            "UnmodifiableView", "FinalFields");
    // the four baseline "absence of a safety guarantee" concepts, plus @StaticSideEffects: not a missing
    // guarantee but a genuine outward effect (this method reaches into global/static state) the designer should
    // always see, so it is rendered with the same attention polarity rather than carried as neutral
    private static final Set<String> NEGATIVE_NAMES = Set.of("Modified", "Mutable", "Dependent", "Nullable",
            "StaticSideEffects");
    // annotations that exist ONLY in an eventual form (the transition protocol, and the after-mark FINAL_FIELDS
    // level which the decorator never emits unconditionally)
    private static final Set<String> ALWAYS_EVENTUAL_NAMES = Set.of("Mark", "Only", "TestMark", "FinalFields");

    private final Qualification.Decorator decorator;
    private final Qualification simpleNames;

    public AnnotationTagger(AnalysisEngine engine, Runtime runtime, SourceSet sourceSetOfRequest) {
        this.decorator = engine.decorator(runtime, sourceSetOfRequest, null);
        this.simpleNames = runtime.qualificationSimpleNames();
    }

    public List<DaemonProtocol.Annotation> tag(Info info) {
        List<DaemonProtocol.Annotation> out = new ArrayList<>();
        Set<String> decorated = new HashSet<>();
        for (AnnotationExpression ae : decorator.annotations(info)) {
            String text = ae.print(simpleNames).toString();
            String name = nameOf(text);
            decorated.add(name);
            if ("UnmodifiableView".equals(name) && immutableResult(info)) {
                // the analysis calls any unmodifiable result a view, a String or a record as much as
                // Collections.unmodifiableList; where the result is immutable, the view is the weaker claim and says
                // nothing new. It is left for the views proper, whose result is mutable through another reference.
                continue;
            }
            if (isEventual(ae, name)) {
                // an eventual verdict is novel information, never implied by the enclosing declaration, so it is
                // never a context default (which the default filter would hide) -- always show it.
                out.add(new DaemonProtocol.Annotation(text, EVENTUAL, false));
            } else if (isModifiedHcOnly(ae, name)) {
                // only the hidden content is modified: the decorator prints it "like @NotModified is", a proven
                // verdict stronger than the @Modified baseline, and never implied by the enclosing declaration
                out.add(new DaemonProtocol.Annotation(text, POSITIVE, false));
            } else {
                out.add(new DaemonProtocol.Annotation(text, polarity(name), positiveContextDefault(info, name)));
            }
        }
        addNegatives(info, out, decorated);
        return refine(info, out, decorated);
    }

    /**
     * Two rules over the whole list. (1) What the author wrote on the element is not shown again as computed: the
     * source already says {@code @IgnoreModifications}, {@code @Finalizer}, {@code @ImmutableContainer}; a computed
     * value that breaks the contract is the guard's finding, not a second annotation (maddi-dist#6). (2) A
     * {@code @Fluent} method returns {@code this}, which makes it {@code @Independent}: implied, so a context default.
     */
    private static List<DaemonProtocol.Annotation> refine(Info info, List<DaemonProtocol.Annotation> out,
                                                          Set<String> decorated) {
        Set<String> written = new HashSet<>();
        for (AnnotationExpression ae : info.annotations()) {
            if (ae.typeInfo() != null) written.add(ae.typeInfo().simpleName());
        }
        boolean fluent = decorated.contains("Fluent");
        if (written.isEmpty() && !fluent) return out;
        List<DaemonProtocol.Annotation> refined = new ArrayList<>(out.size());
        for (DaemonProtocol.Annotation a : out) {
            String name = nameOf(a.text());
            if (written.contains(name)) continue;
            refined.add(fluent && "Independent".equals(name) && !a.contextDefault()
                    ? new DaemonProtocol.Annotation(a.text(), a.polarity(), true) : a);
        }
        return refined;
    }

    /**
     * @param decorated the simple names the decorator already printed for {@code info}. Since
     *                  {@code @Modified(hc=true)} (maddi-mod#25) the decorator prints a {@code @Modified} of its
     *                  own, and the plain baseline beside it would show the element twice, once in each polarity.
     */
    private void addNegatives(Info info, List<DaemonProtocol.Annotation> out, Set<String> decorated) {
        PropertyValueMap a = info.analysis();
        boolean modifiedDecorated = decorated.contains("Modified");
        switch (info) {
            case MethodInfo mi -> {
                if (!mi.isConstructor() && !modifiedDecorated && isModified(a, PropertyImpl.NON_MODIFYING_METHOD)) {
                    out.add(neg("@Modified", true)); // methods modify by default
                }
                if (!mi.isConstructor() && mi.hasReturnValue()) {
                    mutableOrFinalFields(a, PropertyImpl.IMMUTABLE_METHOD, true, out);
                    if (isDependent(a, PropertyImpl.INDEPENDENT_METHOD)) out.add(neg("@Dependent", true));
                    if (!kotlinSource(info)) nullability(a, PropertyImpl.NULLABILITY_METHOD, mi.returnType(), out);
                }
            }
            case FieldInfo fi -> {
                if (!fi.type().isPrimitiveStringClass() && !modifiedDecorated
                    && isModified(a, PropertyImpl.UNMODIFIED_FIELD)) {
                    out.add(neg("@Modified", false)); // a modified field is always informative
                }
                mutableOrFinalFields(a, PropertyImpl.IMMUTABLE_FIELD, true, out);
                if (isDependent(a, PropertyImpl.INDEPENDENT_FIELD)) out.add(neg("@Dependent", true));
                if (!kotlinSource(info)) nullability(a, PropertyImpl.NULLABILITY_FIELD, fi.type(), out);
            }
            case ParameterInfo pi -> {
                if (!pi.parameterizedType().isPrimitiveStringClass() && !modifiedDecorated
                    && isModified(a, PropertyImpl.UNMODIFIED_PARAMETER)) {
                    // a modified parameter — "this method changes your argument" — is always worth showing
                    out.add(neg("@Modified", false));
                }
                if (isMutable(a, PropertyImpl.IMMUTABLE_PARAMETER)) out.add(neg("@Mutable", true));
                if (isDependent(a, PropertyImpl.INDEPENDENT_PARAMETER)) out.add(neg("@Dependent", true));
                if (!kotlinSource(info)) nullability(a, PropertyImpl.NULLABILITY_PARAMETER, pi.parameterizedType(), out);
            }
            case TypeInfo ti -> {
                // a type's own @FinalFields is informative, as its @Immutable is; on a field or a method result it
                // is the same kind of baseline the @Mutable it replaces was
                mutableOrFinalFields(a, PropertyImpl.IMMUTABLE_TYPE, false, out);
                if (isDependent(a, PropertyImpl.INDEPENDENT_TYPE)) out.add(neg("@Dependent", true));
            }
            default -> {
            }
        }
    }

    /**
     * {@code @Mutable}, or {@code @FinalFields} when that is the level: {@link Value.Immutable#isMutable()} counts
     * {@code FINAL_FIELDS} as mutable, and the decorator prints {@code @FinalFields} only in its after-mark form, so
     * without this an all-final type that is still modified read {@code @Mutable}. Parameters keep {@code @Mutable}:
     * {@code @FinalFields} does not target them.
     */
    private static void mutableOrFinalFields(PropertyValueMap a, Property property, boolean finalFieldsContextDefault,
                                             List<DaemonProtocol.Annotation> out) {
        Value.Immutable v = a.getOrNull(property, ValueImpl.ImmutableImpl.class);
        if (v == null || !v.isMutable()) return;
        if (v.isFinalFields()) {
            out.add(new DaemonProtocol.Annotation("@FinalFields", POSITIVE, finalFieldsContextDefault));
        } else {
            out.add(neg("@Mutable", true));
        }
    }

    /**
     * The method's result is at least {@code @Immutable(hc=true)}: by the method's own verdict, or else by its return
     * type's. The second is needed because the analysis leaves the method's verdict implied for a {@code String} or
     * a record result, as the decorator leaves it unprinted.
     */
    private static boolean immutableResult(Info info) {
        if (!(info instanceof MethodInfo mi)) return false;
        Value.Immutable v = mi.analysis().getOrNull(PropertyImpl.IMMUTABLE_METHOD, ValueImpl.ImmutableImpl.class);
        if (v != null && !v.isMutable()) return true;
        ParameterizedType returnType = mi.returnType();
        if (returnType.isPrimitiveStringClass()) return true;
        TypeInfo typeInfo = returnType.arrays() == 0 ? returnType.typeInfo() : null;
        return typeInfo != null && immutableType(typeInfo);
    }

    /** A positive annotation is a context default when the enclosing declaration already implies it. */
    private boolean positiveContextDefault(Info info, String name) {
        TypeInfo owner = ownerType(info);
        if (owner == null) return false; // a type's own @Immutable/@Container etc. is always informative
        boolean container = containerType(owner);
        boolean immutable = immutableType(owner);
        return switch (name) {
            // immutable ⇒ all methods non-modifying; container/immutable ⇒ params non-modified
            case "NotModified" -> info instanceof MethodInfo ? immutable : (container || immutable);
            case "Independent" -> immutable; // immutable ⇒ independent
            default -> false;
        };
    }

    private static TypeInfo ownerType(Info info) {
        return switch (info) {
            case ParameterInfo pi -> pi.methodInfo().typeInfo();
            case MethodInfo mi -> mi.typeInfo();
            case FieldInfo fi -> fi.owner();
            default -> null;
        };
    }

    private static DaemonProtocol.Annotation neg(String text, boolean contextDefault) {
        return new DaemonProtocol.Annotation(text, NEGATIVE, contextDefault);
    }

    /**
     * An eventual (after-mark) annotation: the transition protocol ({@code @Mark}/{@code @Only}/{@code @TestMark})
     * or the after-mark form of an otherwise-unconditional annotation ({@code @Immutable(after=)},
     * {@code @NotModified(after=)}, {@code @Final(after=)}, {@code @FinalFields(after=)}). Detected structurally
     * from the {@link AnnotationExpression} -- the {@code after=} attribute and the type's simple name -- not by
     * string-matching the printed text.
     */
    private static boolean isEventual(AnnotationExpression ae, String name) {
        if (ALWAYS_EVENTUAL_NAMES.contains(name)) return true;
        return POSITIVE_NAMES.contains(name) && !ae.extractString("after", "").isBlank();
    }

    /** {@code @Modified(hc=true)}: detected from the attribute, as {@link #isEventual} detects {@code after=}. */
    private static boolean isModifiedHcOnly(AnnotationExpression ae, String name) {
        return "Modified".equals(name) && ae.extractBoolean("hc");
    }

    private static String polarity(String name) {
        if (POSITIVE_NAMES.contains(name)) return POSITIVE;
        if (NEGATIVE_NAMES.contains(name)) return NEGATIVE;
        return NEUTRAL;
    }

    /** "@Immutable(hc=true)" → "Immutable". */
    private static String nameOf(String text) {
        int start = text.startsWith("@") ? 1 : 0;
        int paren = text.indexOf('(', start);
        return text.substring(start, paren < 0 ? text.length() : paren);
    }

    // ---- value predicates (decided values only; absent = undecided = no negative) ----

    private static boolean isModified(PropertyValueMap a, Property property) {
        Value.Bool v = a.getOrNull(property, ValueImpl.BoolImpl.class);
        return v != null && v.isFalse(); // "unmodified/non-modifying" decided FALSE ⇒ modified
    }

    private static boolean isMutable(PropertyValueMap a, Property property) {
        Value.Immutable v = a.getOrNull(property, ValueImpl.ImmutableImpl.class);
        return v != null && v.isMutable();
    }

    private static boolean isDependent(PropertyValueMap a, Property property) {
        Value.Independent v = a.getOrNull(property, ValueImpl.IndependentImpl.class);
        return v != null && v.isDependent();
    }

    /**
     * The nullability pass's verdict (the daemon runs it, in the analyzer's null-marked reading): {@code @Nullable}
     * is the exception and shown, {@code @NotNull} the norm and hidden by default. The decorator prints neither
     * outside its explicit mode, so both come from here. A primitive has no verdict to show; UNSPECIFIED shows nothing.
     */
    private static void nullability(PropertyValueMap a, Property property, ParameterizedType type,
                                    List<DaemonProtocol.Annotation> out) {
        if (type.isPrimitiveExcludingVoid() && type.arrays() == 0) return;
        Value.Nullability v = a.getOrNull(property, ValueImpl.NullabilityImpl.class);
        if (v == null) return;
        switch (v.state()) {
            case NULLABLE -> out.add(neg("@Nullable", false));
            case NONNULL -> out.add(new DaemonProtocol.Annotation("@NotNull", POSITIVE, true));
            default -> {
            }
        }
    }

    /*
     A declaration in a Kotlin source file: its type says whether it takes null, and the compiler holds it to that, so
     an inferred @Nullable/@NotNull is noise at best. The pass infers for Java, where nothing is declared; on Kotlin it
     put @Nullable on parameters declared non-null (maddi-kotlin-demo Snapshot(lines: List<String>), 2026-10-27).
     */
    private static boolean kotlinSource(Info info) {
        TypeInfo owner = switch (info) {
            case TypeInfo ti -> ti;
            case MethodInfo mi -> mi.typeInfo();
            case FieldInfo fi -> fi.owner();
            case ParameterInfo pi -> pi.methodInfo().typeInfo();
            default -> null;
        };
        if (owner == null) return false;
        TypeInfo primary = owner.primaryType();
        if (primary.compilationUnitOrEnclosingType().isRight()) return false;
        java.net.URI uri = primary.compilationUnit().uri();
        return uri != null && uri.getPath() != null && uri.getPath().endsWith(".kt");
    }

    private static boolean containerType(TypeInfo type) {
        return type.analysis().getOrDefault(PropertyImpl.CONTAINER_TYPE, ValueImpl.BoolImpl.FALSE).isTrue();
    }

    private static boolean immutableType(TypeInfo type) {
        Value.Immutable v = type.analysis().getOrNull(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class);
        return v != null && !v.isMutable();
    }
}
