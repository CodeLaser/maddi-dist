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

package io.codelaser.maddi.ide.eclipse.tests;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.codemining.ICodeMiningProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inline hints reach the Java editor through the platform's code-mining registry: JDT's
 * {@code AbstractTextEditor.installCodeMiningProviders()} asks that registry for providers. A wrong class
 * name or a malformed {@code enabledWhen} in plugin.xml fails silently — no error, just no hints — so this
 * asserts the contribution is really in the registry and really instantiable.
 */
public class MaddiCodeMiningRegistrationTest {

    private static final String EXTENSION_POINT = "org.eclipse.ui.workbench.texteditor.codeMiningProviders";
    private static final String PROVIDER_ID = "io.codelaser.maddi.eclipse.codemining.hints";

    private static IConfigurationElement maddiProvider() {
        return Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor(EXTENSION_POINT))
                .filter(e -> PROVIDER_ID.equals(e.getAttribute("id")))
                .findFirst().orElse(null);
    }

    @DisplayName("the provider is contributed to the platform's code-mining extension point")
    @Test
    public void providerIsRegistered() {
        IConfigurationElement element = maddiProvider();
        assertNotNull(element, () -> "no contribution with id " + PROVIDER_ID + "; registered ids: "
                + Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor(EXTENSION_POINT))
                        .map(e -> e.getAttribute("id")).toList());
        assertEquals("codeMiningProvider", element.getName());
        assertNotNull(element.getAttribute("label"), "the label shows in the preferences UI");
    }

    @DisplayName("the contributed class exists and is a code mining provider")
    @Test
    public void providerClassIsInstantiable() throws Exception {
        IConfigurationElement element = maddiProvider();
        assertNotNull(element);
        // this is exactly what the platform registry does; a typo in class= throws here
        Object provider = element.createExecutableExtension("class");
        assertTrue(provider instanceof ICodeMiningProvider,
                () -> "expected an ICodeMiningProvider, got " + provider.getClass().getName());
    }

    @DisplayName("the provider is scoped to Java sources, so it is not asked about every editor")
    @Test
    public void enabledWhenTargetsJavaSource() {
        IConfigurationElement element = maddiProvider();
        assertNotNull(element);
        IConfigurationElement[] enabledWhen = element.getChildren("enabledWhen");
        assertEquals(1, enabledWhen.length, "expected exactly one enabledWhen expression");
        assertTrue(describe(enabledWhen[0]).contains("org.eclipse.jdt.core.javaSource"),
                "the expression should match on the Java source content type");
    }

    /** Flatten an expression element's attributes, so the test can assert on it without an XML parser. */
    private static String describe(IConfigurationElement element) {
        StringBuilder sb = new StringBuilder(element.getName());
        for (String name : element.getAttributeNames()) {
            sb.append(' ').append(name).append('=').append(element.getAttribute(name));
        }
        for (IConfigurationElement child : element.getChildren()) {
            sb.append(" [").append(describe(child)).append(']');
        }
        return sb.toString();
    }
}
