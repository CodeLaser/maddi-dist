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

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.util.lang.JavaVersion;

/**
 * The JDKs maddi can run on: the IDE's registered Java SDKs of release 25 or later. maddi needs one to start the
 * daemon and to read {@code java.base} from; it is NOT the analyzed project's SDK, which may well be older.
 */
public final class MaddiJdks {
    public static final int MINIMUM_FEATURE = 25;

    private MaddiJdks() {
    }

    /** A Java SDK of release 25 or later, with a home directory. */
    public static boolean suitable(@Nullable Sdk sdk) {
        return sdk != null && sdk.getSdkType() instanceof JavaSdk && sdk.getHomePath() != null
               && feature(sdk) >= MINIMUM_FEATURE;
    }

    /**
     * The JDK's release (25, 26, ...), or 0 when unknown. Read from the version string, NOT through JavaSdkVersion:
     * that enum ends at the newest release the IDE build knows (JDK_26 in 2025.3), and returns null for a JDK 27,
     * which would then never be offered.
     */
    static int feature(Sdk sdk) {
        JavaVersion version = JavaVersion.tryParse(sdk.getVersionString());
        return version == null ? 0 : version.feature;
    }

    /**
     * The JDK to use when the settings name none: the default project's SDK (what new projects get, File → New
     * Projects Setup → Structure) when it is suitable, else the newest suitable registered JDK; null when there is
     * none.
     */
    public static @Nullable Sdk defaultJdk() {
        Sdk projectDefault = ProjectRootManager.getInstance(ProjectManager.getInstance().getDefaultProject())
                .getProjectSdk();
        if (suitable(projectDefault)) return projectDefault;
        return Arrays.stream(ProjectJdkTable.getInstance().getAllJdks())
                .filter(MaddiJdks::suitable)
                .max(Comparator.comparingInt(MaddiJdks::feature))
                .orElse(null);
    }

    /** The registered JDK whose home is {@code home}, or null. */
    public static @Nullable Sdk byHome(String home) {
        if (home == null || home.isBlank()) return null;
        Path wanted = Path.of(home).normalize();
        return Arrays.stream(ProjectJdkTable.getInstance().getAllJdks())
                .filter(sdk -> sdk.getHomePath() != null && Path.of(sdk.getHomePath()).normalize().equals(wanted))
                .findFirst().orElse(null);
    }

    /** The JDK home to run on: the settings', else the default JDK's; empty when neither names one. */
    public static String effectiveHome(MaddiSettings.State settings) {
        String home = settings.jdkHome == null ? "" : settings.jdkHome.trim();
        if (!home.isEmpty()) return home;
        Sdk fallback = defaultJdk();
        return fallback == null ? "" : fallback.getHomePath();
    }
}
