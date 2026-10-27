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

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import io.codelaser.maddi.ide.client.AnalysisModel;
import io.codelaser.maddi.ide.client.KotlinFrontEndInstaller;
import io.codelaser.maddi.ide.plugin.settings.MaddiSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Kotlin front end the daemon needs to analyse {@code .kt} sources: where it is, and getting it when it is not.
 * <p>
 * It is not bundled (the plugin would grow by ~75 MB for every Java-only user); it is downloaded once per maddi
 * version, on the user's say-so, into the IDE's system directory. Until then the daemon analyses the Java half of
 * a mixed project and reports the Kotlin sources as skipped, so declining costs nothing but the Kotlin results.
 */
final class MaddiKotlinFrontEnd {
    private static final Logger LOG = Logger.getInstance(MaddiKotlinFrontEnd.class);
    private static final Pattern DAEMON_JAR = Pattern.compile("maddi-ide-daemon-(.+)\\.jar");

    /** Offered at most once per IDE session: a declined balloon that comes back on every build is nagging. */
    private static final AtomicBoolean OFFERED = new AtomicBoolean();

    /** The source roots last walked and found to hold no Kotlin. */
    private static volatile List<String> lastKotlinFree = List.of();

    private MaddiKotlinFrontEnd() {
    }

    private static KotlinFrontEndInstaller installer() {
        return new KotlinFrontEndInstaller(Path.of(PathManager.getSystemPath(), "maddi", "kotlin-front-end"));
    }

    /**
     * The directory of K2 jars to hand the daemon, or {@code null} for none: the settings override, then the dev
     * fallback ({@code runIde} sets {@code maddi.k2.home} to the build's own jars), then a download for this
     * daemon's version.
     */
    static @Nullable Path home(MaddiSettings.State settings, Path daemonInstallDir) {
        String override = settings.kotlinFrontEndDir == null ? "" : settings.kotlinFrontEndDir.trim();
        if (!override.isEmpty()) return Path.of(override);
        String dev = System.getProperty("maddi.k2.home", "").trim();
        if (!dev.isEmpty()) return Path.of(dev);
        String version = daemonVersion(daemonInstallDir);
        return version == null ? null : installer().installed(version);
    }

    /**
     * The version of the daemon distribution in {@code installDir}, read off its own jar's name. The download must
     * match the daemon, not the plugin: the install directory can be overridden to a different build.
     */
    static @Nullable String daemonVersion(Path installDir) {
        try (Stream<Path> jars = Files.list(installDir.resolve("lib"))) {
            return jars.map(p -> DAEMON_JAR.matcher(p.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(m -> m.group(1))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Offer the download when the project has Kotlin sources the daemon cannot read. Called after a run that went
     * without a front end; on success, {@code afterInstall} re-runs the analysis, which then includes Kotlin.
     */
    static void offerIfNeeded(Project project, AnalysisModel.AnalyzeConfig config, Path daemonInstallDir,
                              Runnable afterInstall) {
        MaddiSettings.State settings = MaddiSettings.getInstance().getState();
        if (!settings.offerKotlinDownload || OFFERED.get()) return;
        List<String> directories = config.moduleSourceSets() == null ? List.of() : config.moduleSourceSets().stream()
                .flatMap(set -> set.sourceDirectories().stream()).toList();
        // a Java project's tree is walked once per change of its source roots, not after every build
        if (directories.equals(lastKotlinFree)) return;
        if (!KotlinFrontEndInstaller.anyKotlinSource(directories)) {
            lastKotlinFree = directories;
            return;
        }
        String version = daemonVersion(daemonInstallDir);
        if (version == null) {
            LOG.info("maddi: Kotlin sources found, but the daemon's version is unknown; not offering a download");
            return;
        }
        if (!OFFERED.compareAndSet(false, true)) return;

        Notification notification = NotificationGroupManager.getInstance().getNotificationGroup("maddi")
                .createNotification("maddi",
                        "This project has Kotlin sources. maddi analyses them with a Kotlin front end that is"
                        + " downloaded separately (about 85 MB, from the maddi " + version + " release on GitHub)."
                        + " Until then, only the Java sources are analysed.",
                        NotificationType.INFORMATION);
        notification.addAction(NotificationAction.createSimpleExpiring("Download Kotlin support",
                () -> download(project, version, afterInstall)));
        notification.addAction(NotificationAction.createSimpleExpiring("Don't ask again",
                () -> MaddiSettings.getInstance().getState().offerKotlinDownload = false));
        notification.notify(project);
    }

    private static void download(Project project, String version, Runnable afterInstall) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "maddi: downloading Kotlin support", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    Path home = installer().install(version,
                            bytes -> {
                                indicator.checkCanceled(); // cancels by throwing; the installer cleans up
                                indicator.setText2((bytes >> 20) + " MB");
                            });
                    LOG.info("maddi: Kotlin front end " + version + " installed at " + home);
                    afterInstall.run();
                } catch (IOException e) {
                    LOG.warn("maddi: Kotlin front end download failed", e);
                    NotificationGroupManager.getInstance().getNotificationGroup("maddi")
                            .createNotification("maddi", "Could not install Kotlin support: " + e.getMessage()
                                    + ". A directory of the K2 jars can be set by hand in Settings → maddi.",
                                    NotificationType.ERROR)
                            .notify(project);
                }
            }
        });
    }
}
