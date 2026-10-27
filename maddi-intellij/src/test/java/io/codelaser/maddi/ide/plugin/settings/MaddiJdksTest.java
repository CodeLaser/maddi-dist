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

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.nio.file.Path;

/**
 * The maddi JDK needs no typing: with nothing in the settings, the default JDK (25+) is used and shown, and the
 * settings page selects it from the IDE's registered JDKs, as Project Structure does.
 */
public class MaddiJdksTest extends LightJavaCodeInsightFixtureTestCase {

    private Sdk jdk;
    private String savedHome;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // this test runs on the IDE's JBR 21; the build hands it the Gradle JVM (25+), registered as the IDE would
        String home = Path.of(System.getProperty("maddi.test.jdkHome")).toString();
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), home);
        jdk = JavaSdk.getInstance().createJdk("maddi-test-jdk", home, false);
        WriteAction.run(() -> ProjectJdkTable.getInstance().addJdk(jdk));
        savedHome = MaddiSettings.getInstance().getState().jdkHome;
        MaddiSettings.getInstance().getState().jdkHome = "";
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            MaddiSettings.getInstance().getState().jdkHome = savedHome;
            WriteAction.run(() -> ProjectJdkTable.getInstance().removeJdk(jdk));
        } finally {
            super.tearDown();
        }
    }

    public void testRegisteredJdk25IsSuitableAndFoundByHome() {
        assertTrue(MaddiJdks.suitable(jdk));
        assertEquals(jdk.getName(), MaddiJdks.byHome(jdk.getHomePath()).getName());
        assertNull(MaddiJdks.byHome("/no/such/jdk"));
    }

    public void testEmptySettingsFallBackToTheDefaultJdk() {
        assertEquals(jdk.getHomePath(), MaddiJdks.effectiveHome(MaddiSettings.getInstance().getState()));
        MaddiSettings.getInstance().getState().jdkHome = "/some/other/jdk";
        assertEquals("/some/other/jdk", MaddiJdks.effectiveHome(MaddiSettings.getInstance().getState()));
    }

    /** The settings page fills in the default JDK when none is set, and OK stores it. */
    public void testSettingsPageSelectsAndStoresTheDefaultJdk() throws Exception {
        MaddiConfigurable configurable = new MaddiConfigurable();
        try {
            configurable.createComponent();
            assertTrue("the filled-in default is a change to store", configurable.isModified());
            configurable.apply();
            assertEquals(jdk.getHomePath(), MaddiSettings.getInstance().getState().jdkHome);
            assertFalse(configurable.isModified());
        } finally {
            configurable.disposeUIResources();
        }
    }

    /** A home typed in before the chooser existed, not registered in the IDE, is kept. */
    public void testUnregisteredHomeIsKept() throws Exception {
        MaddiSettings.getInstance().getState().jdkHome = "/opt/custom-jdk-25";
        MaddiConfigurable configurable = new MaddiConfigurable();
        try {
            configurable.createComponent();
            assertFalse(configurable.isModified());
            configurable.apply();
            assertEquals("/opt/custom-jdk-25", MaddiSettings.getInstance().getState().jdkHome);
        } finally {
            configurable.disposeUIResources();
        }
    }
}
