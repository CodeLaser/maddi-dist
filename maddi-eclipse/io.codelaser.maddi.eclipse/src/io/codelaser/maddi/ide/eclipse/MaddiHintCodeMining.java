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

import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.codemining.ICodeMiningProvider;
import org.eclipse.jface.text.codemining.LineContentCodeMining;

/**
 * One inline annotation label, drawn in the line's content at the declaration it belongs to — the closest
 * Eclipse equivalent of an IntelliJ inline inlay.
 * <p>
 * The label is known at construction (it is computed from an analysis result already in memory), so there is
 * no deferred work: {@code AbstractCodeMining.doResolve} defaults to a completed future, which is what we
 * want. Resolving lazily would only matter if the text were expensive to produce.
 */
public class MaddiHintCodeMining extends LineContentCodeMining {

    public MaddiHintCodeMining(Position position, ICodeMiningProvider provider, String label) {
        super(position, provider);
        setLabel(label);
    }
}
