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

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;

/**
 * A blocking read action. {@code ReadAction.compute} is deprecated from 2026.1 in favour of
 * {@code ReadAction.computeBlocking}, which 2025.3 (our sinceBuild) does not have; the Application method both
 * delegate to exists, undeprecated, in every supported version.
 */
final class ReadActions {
    private ReadActions() {
    }

    static <T> T compute(Computable<T> computable) {
        return ApplicationManager.getApplication().runReadAction(computable);
    }
}
