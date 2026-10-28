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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a maddi result into editor markers, two flavours:
 * <ul>
 *   <li><b>Findings</b> ({@link #VIOLATION_TYPE}, a problem marker) — guard contract violations (Error) and
 *       advisory near misses (Info); these show in the Problems view and underline the code they are about,
 *       with the why-chain in the message. The counterpart of IntelliJ's external annotator.</li>
 *   <li><b>Analysis hints</b> ({@link #HINT_TYPE}, a plain text marker) — the computed annotations of each
 *       type, method and field, the FULL set, as a ruler icon with a tooltip: the counterpart of IntelliJ's gutter
 *       icons. An eventual verdict gets its own icon ({@link #HINT_EVENTUAL_TYPE}): "immutable" and "immutable
 *       once marked" must not look alike at a glance. Hints stay OUT of the Problems view, so they inform without
 *       flooding it. Parameters have none: their hint is inline.</li>
 * </ul>
 * Both are cleared and rebuilt on each analysis, and when a preference that shapes them changes.
 */
public final class MaddiMarkers {

    public static final String VIOLATION_TYPE = MaddiEclipsePlugin.PLUGIN_ID + ".contractViolation";
    public static final String HINT_TYPE = MaddiEclipsePlugin.PLUGIN_ID + ".analysisHint";
    /** A subtype of {@link #HINT_TYPE}, so that clearing the hints clears these too. */
    public static final String HINT_EVENTUAL_TYPE = MaddiEclipsePlugin.PLUGIN_ID + ".analysisHintEventual";

    /** The kinds that get a ruler icon. */
    private static final Set<String> GUTTER_KINDS = Set.of("TYPE", "METHOD", "FIELD");

    private MaddiMarkers() {
    }

    /** Clear all maddi markers under {@code root}, then create fresh ones for the latest result. */
    public static void apply(IWorkspaceRoot root, AnalysisModel.Result result) throws CoreException {
        apply(root, result, MaddiPreferences.showGuardFindings(), MaddiPreferences.showGutterIcons());
    }

    /** As above, with the two preferences given; visible for tests. */
    public static void apply(IWorkspaceRoot root, AnalysisModel.Result result, boolean findings, boolean gutter)
            throws CoreException {
        root.deleteMarkers(VIOLATION_TYPE, true, IResource.DEPTH_INFINITE);
        root.deleteMarkers(HINT_TYPE, true, IResource.DEPTH_INFINITE);
        if (result == null) return;

        Map<IFile, int[]> lineStarts = new HashMap<>();
        if (findings) {
            for (AnalysisModel.Finding finding : result.findings()) {
                IFile file = locate(root, finding.uri());
                if (file != null) createViolation(file, finding, lineStarts.computeIfAbsent(file, MaddiMarkers::lineStarts));
            }
        }
        if (gutter && result.elementAnnotations() != null) {
            for (AnalysisModel.ElementAnnotation element : result.elementAnnotations()) {
                String text = gutterText(element);
                if (text == null) continue;
                IFile file = locate(root, element.uri());
                if (file != null) createHint(file, element, text);
            }
        }
    }

    /** What an element's ruler icon says, or null when it gets none. */
    public static String gutterText(AnalysisModel.ElementAnnotation element) {
        if (!GUTTER_KINDS.contains(element.kind())) return null;
        if (element.displayAnnotations() == null || element.displayAnnotations().isEmpty()) return null;
        return "maddi: " + String.join(" ", element.displayAnnotations());
    }

    private static void createViolation(IFile file, AnalysisModel.Finding finding, int[] lineStarts)
            throws CoreException {
        IMarker marker = file.createMarker(VIOLATION_TYPE);
        marker.setAttribute(IMarker.SEVERITY, severity(finding));
        marker.setAttribute(IMarker.MESSAGE, message(finding));
        if (finding.beginLine() != null) marker.setAttribute(IMarker.LINE_NUMBER, finding.beginLine());
        int[] range = range(lineStarts, finding.beginLine(), finding.beginCol(), finding.endLine(), finding.endCol());
        if (range != null) {
            marker.setAttribute(IMarker.CHAR_START, range[0]);
            marker.setAttribute(IMarker.CHAR_END, range[1]);
        }
    }

    private static void createHint(IFile file, AnalysisModel.ElementAnnotation element, String text)
            throws CoreException {
        IMarker marker = file.createMarker(AnalysisModel.isEventual(element) ? HINT_EVENTUAL_TYPE : HINT_TYPE);
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

    /**
     * The character range [start, end) of maddi's 1-based, end-inclusive coordinates, or null when they are missing
     * or no longer fit the file. A missing end covers one character; a column past its line is clamped onto it.
     */
    public static int[] range(int[] lineStarts, Integer beginLine, Integer beginCol, Integer endLine, Integer endCol) {
        if (lineStarts == null || beginLine == null || beginCol == null) return null;
        int start = offset(lineStarts, beginLine, beginCol);
        if (start < 0) return null;
        int end = endLine == null || endCol == null ? -1 : offset(lineStarts, endLine, endCol + 1);
        if (end <= start) end = start + 1;
        return new int[]{start, end};
    }

    private static int offset(int[] lineStarts, int line, int col) {
        // lineStarts has one extra entry: the end of the text, so that every line has an end
        if (line < 1 || line >= lineStarts.length) return -1;
        int lineStart = lineStarts[line - 1];
        int lineEnd = lineStarts[line];
        return Math.min(lineStart + Math.max(0, col - 1), lineEnd);
    }

    /** The offset at which each line of the text starts, plus the text's length; null when it cannot be read. */
    public static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n')) starts.add(i + 1);
        }
        starts.add(text.length());
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] lineStarts(IFile file) {
        try (InputStream in = file.getContents(true)) {
            return lineStarts(new String(in.readAllBytes(), Charset.forName(file.getCharset())));
        } catch (CoreException | IOException | IllegalArgumentException e) {
            return null; // the marker keeps its line; only the underline is lost
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
