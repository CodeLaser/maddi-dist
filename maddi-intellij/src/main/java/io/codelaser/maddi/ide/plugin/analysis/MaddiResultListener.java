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

package io.codelaser.maddi.ide.plugin.analysis;

import com.intellij.util.messages.Topic;
import io.codelaser.maddi.ide.client.AnalysisModel;
import org.jetbrains.annotations.NotNull;

/** Fired on the project message bus whenever a fresh analysis result is available (tool window etc. subscribe). */
public interface MaddiResultListener {
    Topic<MaddiResultListener> TOPIC = Topic.create("maddi analysis result", MaddiResultListener.class);

    void resultUpdated(@NotNull AnalysisModel.Result result);
}
