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

import org.eclipse.jface.preference.IPreferenceStore;

/**
 * Preference keys and resolved accessors for the maddi daemon settings. Each accessor prefers the workspace
 * preference (Window → Preferences → maddi), then a system property, then an environment variable — so the
 * plugin works out of the box under a dev launch (sysprops) and configured for real users (preferences).
 */
public final class MaddiPreferences {

    public static final String JDK_HOME = "maddi.jdkHome";
    public static final String DAEMON_INSTALL = "maddi.daemonInstall";
    public static final String DAEMON_XMX_MB = "maddi.daemonXmxMb";
    public static final String HINT_FILTER = "maddi.hintFilter";
    public static final String AUTO_ANALYZE_ON_BUILD = "maddi.autoAnalyzeOnBuild";
    public static final String WARN_NEAR_MISSES = "maddi.warnNearMisses";
    public static final String INLINE_HINTS = "maddi.inlineHints";
    public static final String HINT_PLACEMENT = "maddi.hintPlacement";

    public static final int DEFAULT_XMX_MB = 4096;
    public static final HintFilter DEFAULT_HINT_FILTER = HintFilter.HIDE_CONTEXT_DEFAULTS;
    public static final HintPlacement DEFAULT_HINT_PLACEMENT = HintPlacement.ABOVE_DECLARATION;

    private MaddiPreferences() {
    }

    /** Home of the maddi JDK (25+); the analysis SDK AND the daemon's run JDK. */
    public static String jdkHome() {
        return resolve(JDK_HOME, "maddi.jdk.home", "MADDI_JDK_HOME");
    }

    /** The daemon {@code installDist} directory (contains {@code bin/} and {@code lib/}). */
    public static String daemonInstall() {
        return resolve(DAEMON_INSTALL, "maddi.daemon.install", "MADDI_DAEMON_INSTALL");
    }

    public static int daemonXmxMb() {
        int v = store().getInt(DAEMON_XMX_MB);
        return v > 0 ? v : DEFAULT_XMX_MB;
    }

    /** Which computed annotations show as gutter hints. */
    public static HintFilter hintFilter() {
        String v = store().getString(HINT_FILTER);
        if (isBlank(v)) return DEFAULT_HINT_FILTER;
        try {
            return HintFilter.valueOf(v);
        } catch (IllegalArgumentException e) {
            return DEFAULT_HINT_FILTER;
        }
    }

    /** Re-analyze a project automatically after Eclipse builds it. */
    public static boolean autoAnalyzeOnBuild() {
        return store().getBoolean(AUTO_ANALYZE_ON_BUILD);
    }

    /**
     * Ask the analyzer for advisory near-miss warnings: types and methods that narrowly miss a property,
     * e.g. one modifying method away from {@code @Container}. Opt-in, as on the CLI, because they are
     * noisy on code that has not been curated for them.
     */
    public static boolean warnNearMisses() {
        return store().getBoolean(WARN_NEAR_MISSES);
    }

    /**
     * Show the computed annotations inline in the Java editor (code minings), as well as in the gutter.
     * Note that Eclipse also requires <em>Java &gt; Editor &gt; Code Minings &gt; Enable code minings</em>:
     * JDT gates every provider in its editor, ours included, behind that one.
     */
    public static boolean inlineHints() {
        return store().getBoolean(INLINE_HINTS);
    }

    /** Where a declaration's hints are drawn; parameters are always inline regardless. */
    public static HintPlacement hintPlacement() {
        String v = store().getString(HINT_PLACEMENT);
        if (isBlank(v)) return DEFAULT_HINT_PLACEMENT;
        try {
            return HintPlacement.valueOf(v);
        } catch (IllegalArgumentException e) {
            return DEFAULT_HINT_PLACEMENT;
        }
    }

    private static String resolve(String prefKey, String systemProperty, String envVar) {
        String v = store().getString(prefKey);
        if (isBlank(v)) v = System.getProperty(systemProperty);
        if (isBlank(v)) v = System.getenv(envVar);
        return isBlank(v) ? null : v.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static IPreferenceStore store() {
        return MaddiEclipsePlugin.get().getPreferenceStore();
    }
}
