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

package io.codelaser.maddi.ide.eclipse.tests;

import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.ide.eclipse.MaddiMarkers;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The markers of a result, on a real workspace file: a guard finding underlines its range, as IntelliJ's annotator
 * does; the ruler icons carry the FULL annotation set of types, methods and fields, never of parameters, and an
 * eventual verdict gets a marker type of its own; and both follow their preference.
 */
public class MaddiMarkersTest {

    private static final String SOURCE = "class A {\n  void m(int x) {}\n}\n";

    @Test
    @DisplayName("a finding underlines its range; ruler icons on the type and the method, not the parameter")
    public void markersOfAResult() throws Exception {
        IFile file = javaFile();
        String uri = file.getLocationURI().toString();
        AnalysisModel.Result result = result(uri);

        ResourcesPlugin.getWorkspace().run(m -> MaddiMarkers.apply(ResourcesPlugin.getWorkspace().getRoot(), result,
                true, true), null);

        IMarker[] violations = file.findMarkers(MaddiMarkers.VIOLATION_TYPE, false, IResource.DEPTH_ZERO);
        assertEquals(1, violations.length);
        // line 2 starts at offset 10; columns 3..6 (end inclusive) are "void"
        assertEquals(12, violations[0].getAttribute(IMarker.CHAR_START, -1));
        assertEquals(16, violations[0].getAttribute(IMarker.CHAR_END, -1));
        assertEquals("m modifies\n  ⇐ because", violations[0].getAttribute(IMarker.MESSAGE, ""));

        IMarker[] plain = file.findMarkers(MaddiMarkers.HINT_TYPE, false, IResource.DEPTH_ZERO);
        assertEquals(1, plain.length, "the type; the parameter has no ruler icon");
        assertEquals("maddi: @ImmutableContainer @Independent", plain[0].getAttribute(IMarker.MESSAGE, ""));
        IMarker[] eventual = file.findMarkers(MaddiMarkers.HINT_EVENTUAL_TYPE, false, IResource.DEPTH_ZERO);
        assertEquals(1, eventual.length, "the method's verdict holds only after a mark");
        assertEquals(2, eventual[0].getAttribute(IMarker.LINE_NUMBER, -1));
        assertEquals(2, file.findMarkers(MaddiMarkers.HINT_TYPE, true, IResource.DEPTH_ZERO).length,
                "clearing the hints clears the eventual ones too");

        ResourcesPlugin.getWorkspace().run(m -> MaddiMarkers.apply(ResourcesPlugin.getWorkspace().getRoot(), result,
                false, false), null);
        assertEquals(0, file.findMarkers(MaddiMarkers.VIOLATION_TYPE, true, IResource.DEPTH_ZERO).length);
        assertEquals(0, file.findMarkers(MaddiMarkers.HINT_TYPE, true, IResource.DEPTH_ZERO).length);
    }

    @Test
    @DisplayName("maddi's 1-based, end-inclusive coordinates become a character range, clamped onto the line")
    public void ranges() {
        int[] starts = MaddiMarkers.lineStarts(SOURCE);
        assertArrayEquals(new int[]{0, 10, 29, 31, 31}, starts);
        assertArrayEquals(new int[]{0, 5}, MaddiMarkers.range(starts, 1, 1, 1, 5));
        assertArrayEquals(new int[]{12, 13}, MaddiMarkers.range(starts, 2, 3, null, null), "no end: one character");
        assertArrayEquals(new int[]{29, 31}, MaddiMarkers.range(starts, 3, 1, 3, 40), "an end past the line is clamped");
        assertNull(MaddiMarkers.range(starts, 9, 1, 9, 2), "a line the file no longer has");
        assertArrayEquals(new int[]{0, 3, 4}, MaddiMarkers.lineStarts("a\r\nb"), "CRLF is one line break");
    }

    private static AnalysisModel.Result result(String uri) {
        AnalysisModel.Finding finding = new AnalysisModel.Finding(uri, 2, 3, 2, 6, "ERROR", "contract-violation",
                "m modifies", List.of(new AnalysisModel.Finding(uri, 2, 3, 2, 6, "ERROR", "cause", "because",
                List.of())));
        AnalysisModel.ElementAnnotation type = new AnalysisModel.ElementAnnotation(uri, 1, 1, 3, 1, "TYPE", "A",
                List.of("@ImmutableContainer", "@Independent"),
                List.of(new AnalysisModel.Annotation("@ImmutableContainer", "POSITIVE", false)), Map.of());
        AnalysisModel.ElementAnnotation method = new AnalysisModel.ElementAnnotation(uri, 2, 3, 2, 18, "METHOD", "A.m",
                List.of("@NotModified(after=\"mark\")"),
                List.of(new AnalysisModel.Annotation("@NotModified(after=\"mark\")", "EVENTUAL", false)), Map.of());
        AnalysisModel.ElementAnnotation parameter = new AnalysisModel.ElementAnnotation(uri, 2, 10, 2, 14,
                "PARAMETER", "A.m(int):0:x", List.of("@NotModified"),
                List.of(new AnalysisModel.Annotation("@NotModified", "POSITIVE", false)), Map.of());
        return new AnalysisModel.Result("r", List.of(finding), List.of(type, method, parameter), List.of(), 0, 0, 0,
                "CERTIFIED");
    }

    private static IFile javaFile() throws Exception {
        IJavaProject project = MaddiEclipseConfigBuilderTest.javaProject("MaddiMarkerSample");
        IFile file = project.getProject().getFile("A.java");
        byte[] bytes = SOURCE.getBytes(StandardCharsets.UTF_8);
        if (file.exists()) file.setContents(new ByteArrayInputStream(bytes), true, false, null);
        else file.create(new ByteArrayInputStream(bytes), true, null);
        file.setCharset("UTF-8", null);
        return file;
    }
}
