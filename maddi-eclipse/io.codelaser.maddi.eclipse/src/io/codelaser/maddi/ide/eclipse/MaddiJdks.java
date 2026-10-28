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
import org.eclipse.jdt.launching.IVMInstall2;
import org.eclipse.jdt.launching.IVMInstallType;
import org.eclipse.jdt.launching.JavaRuntime;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The JDKs maddi can run on: Eclipse's installed JREs (Java → Installed JREs) of release 25 or later — the
 * counterpart of the IntelliJ plugin's {@code MaddiJdks}. maddi needs one to start the daemon and to read
 * {@code java.base} from; it is NOT the analyzed project's JRE, which may well be older.
 */
public final class MaddiJdks {
    public static final int MINIMUM_FEATURE = 25;

    private MaddiJdks() {
    }

    /** An installed JRE of release 25 or later, with a home directory. */
    public static boolean suitable(IVMInstall vm) {
        return vm != null && vm.getInstallLocation() != null && feature(vm) >= MINIMUM_FEATURE;
    }

    /** The installed JRE's release (25, 26, ...), or 0 when unknown. */
    static int feature(IVMInstall vm) {
        return vm instanceof IVMInstall2 v2 ? feature(v2.getJavaVersion()) : 0;
    }

    /** The feature release of a version string: "25.0.1" → 25, "1.8.0_402" → 8, "27-ea" → 27; 0 when unreadable. */
    public static int feature(String version) {
        if (version == null || version.isBlank()) return 0;
        String v = version.trim();
        if (v.startsWith("1.")) v = v.substring(2);
        int end = 0;
        while (end < v.length() && Character.isDigit(v.charAt(end))) end++;
        return end == 0 ? 0 : Integer.parseInt(v.substring(0, end));
    }

    /** Every suitable installed JRE, newest release first. */
    public static List<IVMInstall> suitableJdks() {
        List<IVMInstall> result = new ArrayList<>();
        for (IVMInstallType type : JavaRuntime.getVMInstallTypes()) {
            for (IVMInstall vm : type.getVMInstalls()) {
                if (suitable(vm)) result.add(vm);
            }
        }
        result.sort(Comparator.comparingInt((IVMInstall vm) -> feature(vm)).reversed());
        return result;
    }

    /**
     * The JDK to use when the preferences name none: the workspace default JRE when it is suitable, else the newest
     * suitable installed JRE; null when there is none.
     */
    public static IVMInstall defaultJdk() {
        IVMInstall workspaceDefault = JavaRuntime.getDefaultVMInstall();
        if (suitable(workspaceDefault)) return workspaceDefault;
        List<IVMInstall> suitable = suitableJdks();
        return suitable.isEmpty() ? null : suitable.getFirst();
    }

    /** The home directory of an installed JRE. */
    public static String home(IVMInstall vm) {
        File location = vm.getInstallLocation();
        return location == null ? null : location.getAbsolutePath();
    }

    /** How a JDK reads in the preference page's list: its name, and its release when the name does not say it. */
    public static String label(IVMInstall vm) {
        String name = vm.getName();
        String release = String.valueOf(feature(vm));
        return name.contains(release) ? name : name + " (" + release + ")";
    }
}
