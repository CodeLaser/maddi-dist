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

package io.codelaser.maddi.ide.eclipse;

import com.fasterxml.jackson.databind.JsonNode;
import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.ide.client.MaddiDaemonProcess;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A library class file's declarations, shown with its attached source (the JDK's src.zip, a sources jar), given the
 * values the last analysis has for them. A library has no position in the analysis, so the daemon's elements are
 * matched by declaring type, kind and signature (name and erased parameter types, from the AST's bindings) and placed
 * on the names they matched: from there on the hints and the ruler treat them as they treat the project's.
 * <p>
 * The daemon is asked once per analysis result for each type on screen, in the background and never during a run:
 * it serves one request at a time, and the run's end asks again ({@link #fetchWanted}).
 */
public final class MaddiLibrary {

    private static final MaddiLibrary INSTANCE = new MaddiLibrary();

    // by top-level type; an empty list: asked, nothing to show (an unknown type, an older daemon)
    private final Map<String, List<AnalysisModel.LibraryElement>> byType = new ConcurrentHashMap<>();
    private final Set<String> wanted = ConcurrentHashMap.newKeySet();

    private MaddiLibrary() {
    }

    public static MaddiLibrary get() {
        return INSTANCE;
    }

    /** A new result: what the daemon said of the library belongs to the previous one. */
    public void reset() {
        byType.clear();
    }

    /**
     * The class file's elements, placed on its source; empty before an analysis, when the class file has no source,
     * or while the daemon is still being asked (the hints are refreshed when it answers).
     */
    public List<AnalysisModel.ElementAnnotation> placed(IClassFile classFile, IDocument document) {
        if (MaddiResults.get().latest() == null) return List.of();
        IType primary = classFile.findPrimaryType();
        if (primary == null) return List.of();
        String type = primary.getFullyQualifiedName('.');
        List<AnalysisModel.LibraryElement> known = byType.get(type);
        if (known == null) {
            wanted.add(type);
            fetchWanted();
            return List.of();
        }
        if (known.isEmpty()) return List.of();
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(classFile);
        parser.setResolveBindings(true);
        ASTNode ast = parser.createAST(null);
        if (!(ast instanceof CompilationUnit unit)) return List.of();
        return place(unit, document, classFile.getHandleIdentifier(), known);
    }

    /** Ask the daemon for the types wanted, unless an analysis is running; then refresh the open editors. */
    public void fetchWanted() {
        if (wanted.isEmpty() || MaddiAnalysis.isRunning()) return;
        MaddiDaemonProcess daemon = MaddiEclipsePlugin.get().daemon();
        if (!daemon.isAlive()) return;
        List<String> types = List.copyOf(wanted);
        wanted.removeAll(types);
        Job job = Job.create("maddi: library annotations", monitor -> {
            Map<String, List<AnalysisModel.LibraryElement>> answer = new HashMap<>();
            types.forEach(t -> answer.put(t, new ArrayList<>()));
            try {
                JsonNode node = daemon.libraryAnnotations("lib-" + System.nanoTime(), types);
                if (AnalysisModel.LIBRARY_RESULT.equals(node.path("type").asText())) {
                    AnalysisModel.LibraryResult result =
                            daemon.client().objectMapper().treeToValue(node, AnalysisModel.LibraryResult.class);
                    for (AnalysisModel.LibraryElement e : result.elements()) {
                        // a nested type's elements come with its top-level type, asked for by the latter's name
                        types.stream().filter(t -> e.type().equals(t) || e.type().startsWith(t + ".")).findFirst()
                                .ifPresent(t -> answer.get(t).add(e));
                    }
                } else {
                    MaddiEclipsePlugin.info("maddi: no library annotations from this daemon: "
                                            + node.path("message").asText(""));
                }
            } catch (Exception e) {
                MaddiEclipsePlugin.log(IStatus.WARNING, "maddi: library annotations failed", e);
            }
            byType.putAll(answer);
            new MaddiCodeMiningRefresher().run();
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    /** The elements placed on the declarations of {@code unit}, whose source {@code document} holds. */
    public static List<AnalysisModel.ElementAnnotation> place(CompilationUnit unit, IDocument document, String uri,
                                                              List<AnalysisModel.LibraryElement> elements) {
        Map<String, AnalysisModel.LibraryElement> byKey = new HashMap<>();
        for (AnalysisModel.LibraryElement e : elements) byKey.put(key(e.type(), e.kind(), e.signature()), e);
        List<AnalysisModel.ElementAnnotation> out = new ArrayList<>();
        declarations(unit, (key, name) -> {
            AnalysisModel.LibraryElement element = byKey.get(key);
            int[] at = element == null ? null : position(document, name);
            if (at != null) out.add(AnalysisModel.place(element, uri, at[0], at[1], at[2], at[3]));
        });
        return out;
    }

    /**
     * A project file's annotations on its declarations as they are NOW. The positions are those of the source the
     * analysis read, and stop pointing at the declaration as soon as an edit moves its line (splitting a class's
     * {@code {} onto a line of its own took the field's hints away). So an element that carries its name (owner and
     * signature, from a daemon of 0.9.6 on) is placed on the declaration of that name, wherever it is now; one whose
     * declaration is gone (renamed, deleted) is dropped rather than shown on whatever took its place. What has no
     * such name (an older daemon, a member of an anonymous or local class) keeps its position. As IntelliJ's
     * {@code MaddiLibraryPlacement.follow}.
     */
    public static List<AnalysisModel.ElementAnnotation> follow(CompilationUnit unit, IDocument document,
                                                               List<AnalysisModel.ElementAnnotation> annotations) {
        Set<String> types = new java.util.HashSet<>();
        declarations(unit, (key, name) -> types.add(key.substring(0, key.indexOf('|'))));
        Map<String, AnalysisModel.ElementAnnotation> byKey = new HashMap<>();
        List<AnalysisModel.ElementAnnotation> out = new ArrayList<>();
        for (AnalysisModel.ElementAnnotation a : annotations) {
            if (a.owner() != null && a.signature() != null && types.contains(a.owner())) {
                byKey.put(key(a.owner(), a.kind(), a.signature()), a);
            } else {
                out.add(a);
            }
        }
        if (byKey.isEmpty()) return annotations;
        declarations(unit, (key, name) -> {
            AnalysisModel.ElementAnnotation a = byKey.get(key);
            int[] at = a == null ? null : position(document, name);
            if (at != null) out.add(a.at(at[0], at[1], at[2], at[3]));
        });
        return out;
    }

    /** Every named declaration of the unit, by its key: of a type, its fields, methods and their parameters. */
    private static void declarations(CompilationUnit unit, java.util.function.BiConsumer<String, SimpleName> sink) {
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                return type(node);
            }

            @Override
            public boolean visit(EnumDeclaration node) {
                return type(node);
            }

            @Override
            public boolean visit(RecordDeclaration node) {
                return type(node);
            }

            @Override
            public boolean visit(AnnotationTypeDeclaration node) {
                return type(node);
            }

            private boolean type(AbstractTypeDeclaration node) {
                ITypeBinding binding = node.resolveBinding();
                if (binding == null || binding.isLocal() || binding.isAnonymous()) return false;
                String type = binding.getErasure().getQualifiedName();
                sink.accept(key(type, "TYPE", type), node.getName());
                return true;
            }

            @Override
            public boolean visit(FieldDeclaration node) {
                String type = declaringType(node);
                for (Object fragment : node.fragments()) {
                    SimpleName name = ((VariableDeclarationFragment) fragment).getName();
                    sink.accept(key(type, "FIELD", name.getIdentifier()), name);
                }
                return false;
            }

            @Override
            public boolean visit(EnumConstantDeclaration node) {
                sink.accept(key(declaringType(node), "FIELD", node.getName().getIdentifier()), node.getName());
                return false;
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                IMethodBinding binding = node.resolveBinding();
                if (binding == null) return false;
                String type = declaringType(node);
                String signature = signature(binding);
                sink.accept(key(type, "METHOD", signature), node.getName());
                for (int i = 0; i < node.parameters().size(); i++) {
                    SimpleName name = ((SingleVariableDeclaration) node.parameters().get(i)).getName();
                    sink.accept(key(type, "PARAMETER", signature + "#" + i), name);
                }
                return false; // a local or anonymous class in a body has no name to be found by
            }
        });
    }

    private static String declaringType(ASTNode member) {
        ASTNode parent = member.getParent();
        while (parent != null && !(parent instanceof AbstractTypeDeclaration)) parent = parent.getParent();
        ITypeBinding binding = parent == null ? null : ((AbstractTypeDeclaration) parent).resolveBinding();
        return binding == null ? "" : binding.getErasure().getQualifiedName();
    }

    /**
     * As the daemon writes it: the name ({@code <init>} for a constructor) and the parameters' erasures, a varargs
     * parameter as the array it is, nested types with dots.
     */
    public static String signature(IMethodBinding binding) {
        StringBuilder sb = new StringBuilder(binding.isConstructor() ? "<init>" : binding.getName()).append('(');
        ITypeBinding[] parameters = binding.getMethodDeclaration().getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(parameters[i].getErasure().getQualifiedName());
        }
        return sb.append(')').toString();
    }

    private static String key(String type, String kind, String signature) {
        return type + "|" + kind + "|" + signature;
    }

    /** The name's range as the daemon writes positions: 1-based, the end column inclusive; null when out of step. */
    private static int[] position(IDocument document, SimpleName name) {
        int start = name.getStartPosition();
        int last = start + Math.max(0, name.getLength() - 1);
        try {
            int beginLine = document.getLineOfOffset(start);
            int endLine = document.getLineOfOffset(last);
            return new int[]{beginLine + 1, start - document.getLineOffset(beginLine) + 1,
                    endLine + 1, last - document.getLineOffset(endLine) + 1};
        } catch (BadLocationException e) {
            return null; // the document is not the source the AST was made from: place nothing rather than guess
        }
    }

}
