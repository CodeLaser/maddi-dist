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

/**
 * Where a declaration's computed annotations are drawn, mirroring the IntelliJ plugin's setting of the same
 * name. {@link #ABOVE_DECLARATION} is the default: it reads like hand-written annotated API source, and a
 * full annotation list is long enough that putting it in front of {@code public class Foo} pushes the
 * declaration off to the right.
 * <p>
 * Parameters are always inline whatever this says — a parameter's annotation belongs next to the parameter.
 * <p>
 * In Eclipse the choice has a second consequence: {@link #INLINE} uses a line-content code mining, which has
 * to borrow a character's glyph box and can leave that character showing the mining's own styling
 * (Eclipse bug 531769). {@link #ABOVE_DECLARATION} uses a line-header mining and is free of it. See
 * {@link MaddiHintLineHeaderCodeMining}.
 */
public enum HintPlacement {

    ABOVE_DECLARATION("Above the declaration"),
    INLINE("Inline, at the declaration");

    private final String label;

    HintPlacement(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
