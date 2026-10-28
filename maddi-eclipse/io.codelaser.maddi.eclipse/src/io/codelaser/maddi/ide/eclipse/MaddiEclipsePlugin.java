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

import io.codelaser.maddi.ide.client.MaddiDaemonProcess;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * Bundle activator. Owns the one long-lived maddi daemon process for the workspace (the same
 * {@link MaddiDaemonProcess} the IntelliJ front-end uses, from the shared {@code maddi-ide-client} jar),
 * and closes it on shutdown. Extends {@link AbstractUIPlugin} for a preference store (see
 * {@link MaddiPreferences}).
 */
public class MaddiEclipsePlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "io.codelaser.maddi.eclipse";

    private static MaddiEclipsePlugin instance;
    private final MaddiDaemonProcess daemon = new MaddiDaemonProcess();
    private final MaddiBuildListener buildListener = new MaddiBuildListener();
    private final MaddiCodeMiningRefresher miningRefresher = new MaddiCodeMiningRefresher();

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        // Always listen; the listener itself checks the auto-analyze preference, so toggling it needs no
        // re-registration and costs nothing while off.
        ResourcesPlugin.getWorkspace().addResourceChangeListener(buildListener, IResourceChangeEvent.POST_BUILD);
        // an analysis changes no document, so nothing else would re-query the editors' code minings
        MaddiResults.get().addListener(miningRefresher);
        // nor does a preference: redraw what it shapes
        getPreferenceStore().addPropertyChangeListener(preferenceListener);
    }

    private final IPropertyChangeListener preferenceListener = event -> {
        String key = event.getProperty();
        if (MaddiPreferences.SHOW_GUARD_FINDINGS.equals(key) || MaddiPreferences.SHOW_GUTTER_ICONS.equals(key)) {
            Job.create("maddi: updating markers", monitor -> {
                ResourcesPlugin.getWorkspace().run(m -> MaddiMarkers.apply(ResourcesPlugin.getWorkspace().getRoot(),
                        MaddiResults.get().latest()), monitor);
            }).schedule();
        } else if (MaddiPreferences.HINT_FILTER.equals(key) || MaddiPreferences.INLINE_HINTS.equals(key)
                   || MaddiPreferences.HINT_PLACEMENT.equals(key)) {
            miningRefresher.run();
        }
    };

    @Override
    public void stop(BundleContext context) throws Exception {
        getPreferenceStore().removePropertyChangeListener(preferenceListener);
        MaddiResults.get().removeListener(miningRefresher);
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(buildListener);
        daemon.close();
        instance = null;
        super.stop(context);
    }

    public static MaddiEclipsePlugin get() {
        return instance;
    }

    /** The shared warm-daemon handle; launched lazily by {@link MaddiDaemonProcess#ensureStarted}. */
    public MaddiDaemonProcess daemon() {
        return daemon;
    }

    public static void log(int severity, String message, Throwable t) {
        MaddiEclipsePlugin p = instance;
        if (p != null) p.getLog().log(new Status(severity, PLUGIN_ID, message, t));
    }

    public static void error(String message, Throwable t) {
        log(IStatus.ERROR, message, t);
    }

    public static void info(String message) {
        log(IStatus.INFO, message, null);
    }
}
