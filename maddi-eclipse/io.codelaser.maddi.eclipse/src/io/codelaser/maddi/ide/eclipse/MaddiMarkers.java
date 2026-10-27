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

import io.codelaser.maddi.ide.client.AnalysisModel;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;

import java.net.URI;
import java.util.List;

/**
 * Turns a maddi result into editor markers, two flavours:
 * <ul>
 *   <li><b>Findings</b> ({@link #VIOLATION_TYPE}, a problem marker) — guard contract violations (Error) and
 *       advisory near misses (Info); these show in the Problems view and on the editor ruler, with the
 *       why-chain in the message.</li>
 *   <li><b>Analysis hints</b> ({@link #HINT_TYPE}, a plain text marker) — the computed annotations
 *       ({@code @Container}, {@code @NotModified}, …) on each declaration; these show as a gutter icon +
 *       hover in the editor (mapped to an annotation type in plugin.xml) and stay OUT of the Problems view,
 *       so they inform without flooding it.</li>
 * </ul>
 * Both are cleared and rebuilt on each analysis.
 */
public final class MaddiMarkers {

    public static final String VIOLATION_TYPE = MaddiEclipsePlugin.PLUGIN_ID + ".contractViolation";
    public static final String HINT_TYPE = MaddiEclipsePlugin.PLUGIN_ID + ".analysisHint";

    private MaddiMarkers() {
    }

    /** Clear all maddi markers under {@code root}, then create fresh ones for the latest result. */
    public static void apply(IWorkspaceRoot root, AnalysisModel.Result result) throws CoreException {
        root.deleteMarkers(VIOLATION_TYPE, true, IResource.DEPTH_INFINITE);
        root.deleteMarkers(HINT_TYPE, true, IResource.DEPTH_INFINITE);

        for (AnalysisModel.Finding finding : result.findings()) {
            IFile file = locate(root, finding.uri());
            if (file != null) createViolation(file, finding);
        }
        HintFilter filter = MaddiPreferences.hintFilter();
        for (AnalysisModel.ElementAnnotation element : result.elementAnnotations()) {
            String text = filter.textFor(element);
            if (text.isEmpty()) continue; // nothing to show under the current filter
            IFile file = locate(root, element.uri());
            if (file != null) createHint(file, element, text);
        }
    }

    private static void createViolation(IFile file, AnalysisModel.Finding finding) throws CoreException {
        IMarker marker = file.createMarker(VIOLATION_TYPE);
        marker.setAttribute(IMarker.SEVERITY, severity(finding));
        marker.setAttribute(IMarker.MESSAGE, message(finding));
        if (finding.beginLine() != null) marker.setAttribute(IMarker.LINE_NUMBER, finding.beginLine());
    }

    private static void createHint(IFile file, AnalysisModel.ElementAnnotation element, String text)
            throws CoreException {
        IMarker marker = file.createMarker(HINT_TYPE);
        marker.setAttribute(IMarker.MESSAGE, text);
        if (element.beginLine() != null) marker.setAttribute(IMarker.LINE_NUMBER, element.beginLine());
    }

    /**
     * Near misses are advisory — a suggestion to consider an annotation, not a defect — so they show as
     * Info; everything else follows the finding's own severity. They stay in the Problems view (unlike the
     * hint markers), since they are things to act on, just not problems.
     */
    private static int severity(AnalysisModel.Finding finding) {
        if (AnalysisModel.isNearMiss(finding)) return IMarker.SEVERITY_INFO;
        return "ERROR".equals(finding.severity()) ? IMarker.SEVERITY_ERROR : IMarker.SEVERITY_WARNING;
    }

    /** Message with a compact why-chain appended, one cause per line for readable tooltips. */
    private static String message(AnalysisModel.Finding finding) {
        StringBuilder sb = new StringBuilder(finding.message() == null ? "" : finding.message());
        appendCauses(sb, finding.causes(), 1);
        return sb.toString();
    }

    private static void appendCauses(StringBuilder sb, List<AnalysisModel.Finding> causes, int depth) {
        if (causes == null || causes.isEmpty() || depth > 8) return;
        for (AnalysisModel.Finding cause : causes) {
            sb.append('\n').append("  ".repeat(depth)).append("⇐ ").append(cause.message());
            appendCauses(sb, cause.causes(), depth + 1);
        }
    }

    /** maddi returns {@code file:///abs/File.java}; find the workspace file at that filesystem location. */
    private static IFile locate(IWorkspaceRoot root, String uri) {
        if (uri == null) return null;
        try {
            IFile[] files = root.findFilesForLocationURI(URI.create(uri));
            return files.length == 0 ? null : files[0];
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
