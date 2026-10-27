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

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Application-level persisted settings. The most important field is {@link State#jdkHome}: maddi needs a
 * <b>JDK 25+</b> both to run the daemon and to read {@code java.base} (a JBR 21 / older SDK triggers
 * internal failures), so this is a dedicated "maddi JDK", not the analyzed project's SDK (which may
 * target 17/21). The settings UI ({@code Configurable}) is added in M4.
 */
@Service(Service.Level.APP)
@State(name = "MaddiSettings", storages = @Storage("maddi.xml"))
public final class MaddiSettings implements PersistentStateComponent<MaddiSettings.State> {

    public static final class State {
        /** Home of a JDK 25+ to run the daemon on and to analyze against. Empty: the default JDK (MaddiJdks). */
        public String jdkHome = "";
        /** Override for the daemon distribution dir; empty = use the copy bundled in the plugin. */
        public String daemonInstallDir = "";
        /** Max heap for the daemon JVM, in MB. */
        public int daemonXmxMb = 4096;
        public boolean autoAnalyzeOnBuild = true;
        public boolean showGuardFindings = true;
        public InlineHintsMode inlineHintsMode = InlineHintsMode.HIDE_CONTEXT_DEFAULTS;
        public HintPlacement hintPlacement = HintPlacement.ABOVE_DECLARATION;
        public boolean showGutterIcons = true;
        /** Advisory "one member away from @Container/@Immutable/…" warnings; off, as on the CLI. */
        public boolean warnNearMisses = false;
        /** A directory of K2 jars for Kotlin sources, overriding the download; empty = download on demand. */
        public String kotlinFrontEndDir = "";
        /** Offer to download the Kotlin front end when a project has Kotlin sources and none is installed. */
        public boolean offerKotlinDownload = true;
    }

    private final State state = new State();

    public static MaddiSettings getInstance() {
        return ApplicationManager.getApplication().getService(MaddiSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State toLoad) {
        XmlSerializerUtil.copyBean(toLoad, this.state);
    }
}
