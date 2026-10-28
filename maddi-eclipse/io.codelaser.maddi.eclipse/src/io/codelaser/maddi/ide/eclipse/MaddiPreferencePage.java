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

import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.DirectoryFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import java.util.ArrayList;
import java.util.List;

/**
 * Window → Preferences → maddi: the maddi JDK (25+), the daemon, and what the editor shows. Mirrors the IntelliJ
 * plugin's settings, Kotlin aside.
 */
public class MaddiPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public MaddiPreferencePage() {
        super(GRID);
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(MaddiEclipsePlugin.get().getPreferenceStore());
        setDescription("maddi runs its analysis in an out-of-process daemon on a JDK 25+ (NOT the analyzed "
                + "project's JRE). The list holds the JDKs 25+ under Java → Installed JREs; add one there if it is "
                + "empty.");
    }

    @Override
    protected void createFieldEditors() {
        addField(new ComboFieldEditor(MaddiPreferences.JDK_HOME,
                "maddi JDK (25+):", jdkChoices(), getFieldEditorParent()));
        addField(new DirectoryFieldEditor(MaddiPreferences.DAEMON_INSTALL,
                "Daemon install override:", getFieldEditorParent()));
        IntegerFieldEditor xmx = new IntegerFieldEditor(MaddiPreferences.DAEMON_XMX_MB,
                "Daemon max heap (MB):", getFieldEditorParent());
        xmx.setValidRange(512, 131072);
        addField(xmx);

        addField(new BooleanFieldEditor(MaddiPreferences.AUTO_ANALYZE_ON_BUILD,
                "Re-analyze automatically after a build (Eclipse builds on every save)", getFieldEditorParent()));
        addField(new BooleanFieldEditor(MaddiPreferences.SHOW_GUARD_FINDINGS,
                "Mark guard contract violations", getFieldEditorParent()));

        addField(new BooleanFieldEditor(MaddiPreferences.INLINE_HINTS,
                "Show hints in the editor (needs Java > Editor > Code Minings enabled)",
                getFieldEditorParent()));
        addField(new ComboFieldEditor(MaddiPreferences.HINT_FILTER,
                "Inline hints:", hintFilterChoices(), getFieldEditorParent()));
        addField(new ComboFieldEditor(MaddiPreferences.HINT_PLACEMENT,
                "Hint placement:", hintPlacementChoices(), getFieldEditorParent()));
        addField(new BooleanFieldEditor(MaddiPreferences.SHOW_GUTTER_ICONS,
                "Show gutter icons on analyzed declarations (the tooltip has the full set)", getFieldEditorParent()));

        addField(new BooleanFieldEditor(MaddiPreferences.WARN_NEAR_MISSES,
                "Warn about near misses (types/methods that narrowly miss a property)", getFieldEditorParent()));
    }

    /**
     * {label, stored-value} pairs for the JDK combo: the default first (stored as empty, so that it follows the
     * installed JREs), then every installed JDK 25+, and a home typed in before this list existed when it is none
     * of those, so that it is kept unless another is picked.
     */
    private String[][] jdkChoices() {
        List<String[]> choices = new ArrayList<>();
        IVMInstall fallback = MaddiJdks.defaultJdk();
        choices.add(new String[]{fallback == null
                ? "Default (none installed: add a JDK 25+ under Java → Installed JREs)"
                : "Default: " + MaddiJdks.label(fallback), ""});
        List<String> homes = new ArrayList<>();
        for (IVMInstall vm : MaddiJdks.suitableJdks()) {
            String home = MaddiJdks.home(vm);
            homes.add(home);
            choices.add(new String[]{MaddiJdks.label(vm), home});
        }
        String stored = getPreferenceStore().getString(MaddiPreferences.JDK_HOME).trim();
        if (!stored.isEmpty() && !homes.contains(stored)) {
            choices.add(new String[]{stored + " (not an installed JRE)", stored});
        }
        return choices.toArray(new String[0][]);
    }

    /** {label, stored-value} pairs for the hint-filter combo, straight from the enum. */
    private static String[][] hintFilterChoices() {
        HintFilter[] values = HintFilter.values();
        String[][] choices = new String[values.length][2];
        for (int i = 0; i < values.length; i++) {
            choices[i][0] = values[i].label();
            choices[i][1] = values[i].name();
        }
        return choices;
    }

    /** {label, stored-value} pairs for the placement combo. */
    private static String[][] hintPlacementChoices() {
        HintPlacement[] values = HintPlacement.values();
        String[][] choices = new String[values.length][2];
        for (int i = 0; i < values.length; i++) {
            choices[i][0] = values[i].label();
            choices[i][1] = values[i].name();
        }
        return choices;
    }
}
