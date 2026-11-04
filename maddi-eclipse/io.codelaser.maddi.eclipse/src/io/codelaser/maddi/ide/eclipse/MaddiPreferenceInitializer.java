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

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

/**
 * Seeds preference defaults. The JDK and the daemon install have none: an empty JDK means the default among the
 * installed JREs, an empty install the daemon bundled in the plugin. Re-analysing after a build stays off, unlike in
 * IntelliJ: Eclipse builds on every save, which would mean a whole-project analysis per save.
 */
public class MaddiPreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = MaddiEclipsePlugin.get().getPreferenceStore();
        store.setDefault(MaddiPreferences.DAEMON_XMX_MB, MaddiPreferences.DEFAULT_XMX_MB);
        store.setDefault(MaddiPreferences.HINT_FILTER, MaddiPreferences.DEFAULT_HINT_FILTER.name());
        store.setDefault(MaddiPreferences.AUTO_ANALYZE_ON_BUILD, false);
        store.setDefault(MaddiPreferences.WARN_NEAR_MISSES, false);
        store.setDefault(MaddiPreferences.INLINE_HINTS, true);
        store.setDefault(MaddiPreferences.HINT_PLACEMENT, MaddiPreferences.DEFAULT_HINT_PLACEMENT.name());
        store.setDefault(MaddiPreferences.SHOW_GUARD_FINDINGS, true);
        store.setDefault(MaddiPreferences.SHOW_GUTTER_ICONS, true);
        store.setDefault(MaddiPreferences.INCREMENTAL_REANALYSIS, false);
    }
}
