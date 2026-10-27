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

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

/**
 * Resolves the daemon {@code installDist} directory (a {@code bin/} launcher + {@code lib/*.jar}). Prefers a
 * configured location (preference / system property / env), else the copy bundled inside this plugin under
 * {@code daemon/}. The bundle declares {@code Eclipse-BundleShape: dir} so it is installed unpacked and the
 * daemon can be launched directly from disk.
 */
public final class MaddiDaemonInstall {

    private MaddiDaemonInstall() {
    }

    public static Path resolve() throws IOException {
        String configured = MaddiPreferences.daemonInstall();
        if (configured != null) return Path.of(configured);

        Bundle bundle = FrameworkUtil.getBundle(MaddiDaemonInstall.class);
        if (bundle == null) return null;
        URL entry = FileLocator.find(bundle, IPath.fromOSString("daemon"), null);
        if (entry == null) return null; // daemon not bundled (dev build without the copy step)
        URL fileUrl = FileLocator.toFileURL(entry);
        return new File(fileUrl.getPath()).toPath();
    }
}
