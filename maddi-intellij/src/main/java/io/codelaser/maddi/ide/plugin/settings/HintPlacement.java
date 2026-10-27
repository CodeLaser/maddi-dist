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

package io.codelaser.maddi.ide.plugin.settings;

/**
 * Where a declaration's computed annotations are drawn.
 * <p>
 * {@link #ABOVE_DECLARATION} is the default because it reads like hand-written annotated API source — the
 * form these annotations take when someone writes them out — and because a full list
 * ({@code @ImmutableContainer(hc=true) @Independent}) is long enough that wedging it in front of
 * {@code public class Foo} pushes the declaration off to the right.
 * <p>
 * Parameters are always inline regardless: a parameter's annotation belongs next to the parameter, and
 * there is no sensible line of its own to put it on.
 */
public enum HintPlacement {

    /** On their own line above the declaration, indented to match it. */
    ABOVE_DECLARATION("Above the declaration"),

    /** Immediately after the declaration's name, on the same line. */
    INLINE("Inline, after the name");

    private final String label;

    HintPlacement(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
