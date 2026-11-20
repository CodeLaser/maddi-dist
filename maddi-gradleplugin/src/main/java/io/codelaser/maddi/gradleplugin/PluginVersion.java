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

package io.codelaser.maddi.gradleplugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * The maddi version this plugin was built with, from the {@code maddi-version.properties} resource the build writes
 * next to this class. It names the release whose Kotlin distribution the plugin downloads: the driver shares the
 * configuration format with the plugin, so the two must come from the same release.
 */
public final class PluginVersion {
    static final String RESOURCE = "maddi-version.properties";

    private PluginVersion() {
    }

    public static String maddiVersion() {
        try (InputStream in = PluginVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("this plugin jar carries no " + RESOURCE);
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("maddiVersion");
            if (version == null || version.isBlank()) {
                throw new IllegalStateException(RESOURCE + " names no maddiVersion");
            }
            return version.trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
