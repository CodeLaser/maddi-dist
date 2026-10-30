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

package io.codelaser.maddi.mcp;

import io.codelaser.maddi.ide.client.AnalysisModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class OverviewTest {

    private static AnalysisModel.ElementAnnotation e(String kind, String fqn, String... annotations) {
        return new AnalysisModel.ElementAnnotation("file:///p/X.java", 1, 1, 1, 1, kind, fqn, List.of(annotations),
                List.of(), Map.of());
    }

    private static final AnalysisModel.Result RESULT = new AnalysisModel.Result("r", List.of(
            new AnalysisModel.Finding(null, null, null, null, null, "ERROR", "contract-violation", "a.Bag breaks it",
                    List.of())),
            List.of(e("TYPE", "a.Bag", "@Container", "@FinalFields"),
                    e("TYPE", "a.Point", "@ImmutableContainer"),
                    e("TYPE", "a.Point.Builder", "@Mutable"),
                    e("TYPE", "a.Lazy", "@Immutable(hc=true,after=\"m1,m2,m3,m4\")"),
                    e("TYPE", "b.Pair", "@ImmutableContainer(hc=true)"),
                    e("TYPE", "b.Plain", "@Immutable"),
                    e("TYPE", "b.Config", "@Immutable(hc=true,after=\"host,port\")"),
                    e("METHOD", "a.Bag.<init>()"),
                    e("METHOD", "a.Bag.add(String)", "@Modified"),
                    e("METHOD", "a.Bag.touch()", "@Modified(hc=true)"),
                    e("METHOD", "a.Bag.size()", "@NotModified"),
                    e("FIELD", "a.Bag.items", "@Modified"),
                    e("FIELD", "a.Point.x"),
                    e("PARAMETER", "a.Bag.fill(java.util.List):0:target", "@Modified")),
            List.of(), 0, 0, 1, "CERTIFIED");

    @Test
    @SuppressWarnings("unchecked")
    public void theWholeProject() {
        Map<String, Object> overview = Overview.of(RESULT, null, 30);
        Map<String, Object> types = (Map<String, Object>) overview.get("types");
        assertEquals(7, types.get("total"));
        Map<String, Map<String, Object>> byLevel = (Map<String, Map<String, Object>>) types.get("byLevel");
        assertEquals(List.of("@ImmutableContainer", "@Immutable", "@ImmutableContainer(hc=true)",
                "@Immutable(hc=true,after=…)",
                "@FinalFields", "@Mutable"), List.copyOf(byLevel.keySet()), "strongest first, rosters left out");
        assertEquals(List.of("a.Point"), byLevel.get("@ImmutableContainer").get("elements"));
        assertEquals(List.of("a.Lazy", "b.Config"), byLevel.get("@Immutable(hc=true,after=…)").get("elements"),
                "one group whatever the marks, sorted");

        Map<String, Object> methods = (Map<String, Object>) overview.get("methods");
        assertEquals(3, methods.get("total"), "not the constructor");
        assertEquals(List.of("a.Bag.add(String)"), ((Map<String, Object>) methods.get("modifying")).get("elements"));
        assertEquals(1, ((Map<String, Object>) methods.get("modifyingHiddenContentOnly")).get("count"));
        assertEquals(1, methods.get("notModified"));
        assertEquals(1, ((Map<String, Object>) ((Map<String, Object>) overview.get("fields")).get("modified"))
                .get("count"));
        assertEquals(1, ((Map<String, Object>) overview.get("modifiedParameters")).get("count"));
        assertEquals(Map.of("ERROR contract-violation", 1), overview.get("findings"));

        Map<String, Map<String, Integer>> packages = (Map<String, Map<String, Integer>>) overview.get("packages");
        assertEquals(Map.of("types", 4, "immutable", 2, "mutable", 1), packages.get("a"),
                "a.Point.Builder is in package a, nested in a.Point");
        assertEquals(Map.of("types", 3, "immutable", 3), packages.get("b"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void oneTypeAndALimit() {
        Map<String, Object> bag = Overview.of(RESULT, "a.Bag", 30);
        assertEquals(1, ((Map<String, Object>) bag.get("types")).get("total"));
        assertNull(bag.get("packages"), "one package: no table");
        assertEquals(Map.of("ERROR contract-violation", 1), bag.get("findings"), "named in the message");

        Map<String, Object> limited = Overview.of(RESULT, "a.", 1);
        Map<String, Object> modifiedOrNot = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) limited.get("types")).get("byLevel")).get("@FinalFields");
        assertEquals(1, modifiedOrNot.get("count"));
        assertNull(modifiedOrNot.get("more"));
    }
}
