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

// The IntelliJ plugin front-end. Deliberately standalone: it does NOT apply
// java-library-conventions and has NO maddi dependency. It runs on the IDE's JBR (21) and speaks
// only plain JSON to the maddi daemon (JDK 25) over a loopback socket, so the JDK split stays clean.

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("java")
    // version omitted: the settings plugin (org.jetbrains.intellij.platform.settings) already puts
    // the IntelliJ Platform Gradle Plugin on the build classpath.
    id("org.jetbrains.intellij.platform")
    // the dist tier's compile-time wall (applied by java-library-conventions elsewhere; this module does not use it)
    id("maddi-tier-guard")
}

// maddi (base) and maddi-mod modules are reached by coordinate; settings.gradle.kts includes their builds
val maddiVersion: String by project

group = "io.codelaser"
// No version here: it comes from the root gradle.properties, the one release train (PUBLISHING.md).
// This is the user-visible version on JetBrains Marketplace, which rejects a version it has already
// seen, so it must move with every published build -- and it dates the daemon it bundles, which IS
// maddi. It used to say 0.1.0 while the project was at 0.9.1.

// Target the IDE's runtime (JBR 21). Compile with the Gradle daemon JDK (26) but emit 21 bytecode.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    intellijPlatform {
        // Since 2025.3 Community/Ultimate ship as one distribution (license-tiered), so use intellijIdea(...).
        intellijIdea("2025.3")
        bundledPlugin("com.intellij.java") // Java PSI, for mapping analysis results onto declarations
        bundledPlugin("org.jetbrains.kotlin") // Kotlin PSI, the same mapping on .kt (an OPTIONAL dependency)
        // Platform + Java test fixtures (LightJavaCodeInsightFixtureTestCase etc.) for surface tests.
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    // IDE-agnostic daemon client (DTOs, socket client, launcher). Brings Jackson transitively; the plugin
    // only uses it internally (never across the platform boundary), so bundling our own copy is safe.
    implementation(project(":maddi-ide-client"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.0.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The IntelliJ platform test fixtures are JUnit 4 (LightJavaCodeInsightFixtureTestCase extends
    // junit.framework.TestCase), needed at compile time; the vintage engine runs them under the JUnit
    // Platform alongside our JUnit 5 tests.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:6.0.3")
}

intellijPlatform {
    projectName = "maddi" // deterministic plugin directory name (bundled daemon lives under <plugin>/daemon)
    buildSearchableOptions = false // optional headless-IDE indexing step; not needed and fails in CI/sandbox
    // Updates after the first, hand-made upload: publishPlugin -PintellijPlatformPublishingToken=<token>, a Marketplace
    // personal access token. Never in a file. Unsigned: signing needs a certificate we do not have (PUBLISHING.md).
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }
    pluginConfiguration {
        // the section of docs/ide-changelog.md for the version being built; required content on Marketplace
        changeNotes = provider { changeNotesFor(project.version.toString()) }
        ideaVersion {
            sinceBuild = "253" // 2025.3
            untilBuild = provider { null } // open-ended
        }
    }
    // JetBrains Marketplace runs the IntelliJ Plugin Verifier on every upload, and API violations block
    // approval -- so run it here rather than discovering it during review. `recommended()` resolves the
    // same set `printProductsReleases` prints: with an open-ended untilBuild that is every release from
    // sinceBuild up, currently IU 2025.3 / 2026.1 / 2026.2. Each is a multi-GB download, cached in ~/.gradle.
    pluginVerification {
        ides {
            recommended()
        }
        // What must fail the build: the default set minus experimental and deprecated usage. INTERNAL API fails
        // it: the Marketplace review refuses a plugin that uses any (0.9.6, 2026-11-03, for
        // DeclarativeInlayHintsPassFactory.Companion.resetModificationStamp(); scheduleRecompute is the public
        // way to recompute inlays, see MaddiAnalysisService.recomputeHints). Still accepted:
        //   EXPERIMENTAL AboveLineIndentedPosition -- the above-line hint placement; no stable counterpart.
        //   DEPRECATED   ReadAction.compute(ThrowableComputable), deprecated in 2026.1. Replacing it means
        //                NonBlockingReadAction and different cancellation semantics -- a real change, not
        //                an import swap, so it is deliberately still open.
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.COMPATIBILITY_WARNINGS,
            FailureLevel.INTERNAL_API_USAGES,
            FailureLevel.INVALID_PLUGIN,
            FailureLevel.MISSING_DEPENDENCIES,
            FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
            FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            FailureLevel.OVERRIDE_ONLY_API_USAGES,
            FailureLevel.NON_EXTENDABLE_API_USAGES,
        )
    }
}

/**
 * The change notes of one release, as HTML: its `## <version>` section of docs/ide-changelog.md, whose bullets are
 * plain text with `**bold**` and `` `code` ``. Fails the build when the section is missing, so a release cannot go out
 * with the previous release's notes, or none.
 */
fun changeNotesFor(version: String): String {
    val lines = rootProject.file("docs/ide-changelog.md").readLines()
    val start = lines.indexOfFirst { it.trim() == "## $version" }
    if (start < 0) throw GradleException("docs/ide-changelog.md has no '## $version' section for the change notes")
    val section = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
    val items = mutableListOf<String>()
    for (line in section) {
        when {
            line.startsWith("- ") -> items += line.removePrefix("- ").trim()
            line.isNotBlank() && items.isNotEmpty() -> items[items.size - 1] = items.last() + " " + line.trim()
        }
    }
    fun inline(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        .replace(Regex("`(.+?)`"), "<code>$1</code>")
    return items.joinToString("", "<ul>", "</ul>") { "<li>${inline(it)}</li>" }
}

// The licence, the notice and the third-party list go at the plugin's root, beside lib/ and daemon/: the zip bundles
// the daemon and some 20 third-party libraries, and shipped no licence text at all until 0.9.6.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask> {
    from(rootProject.layout.projectDirectory) {
        include("LICENSE", "NOTICE", "THIRD-PARTY.txt")
        into("maddi")
    }
}

// Bundle the maddi daemon distribution inside the plugin, at <plugin>/daemon, for both runIde and the
// installable zip. MaddiAnalysisService.resolveInstallDir() falls back to getPluginPath()/daemon.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask> {
    dependsOn(":maddi-ide-daemon:installDist")
    from(project(":maddi-ide-daemon").layout.buildDirectory.dir("install/maddi-ide-daemon")) {
        into("maddi/daemon")
    }
}

// The Kotlin front end is NOT bundled: a user downloads it on demand (MaddiKotlinFrontEnd). For runIde, the build's
// own K2 jars are gathered into a directory and handed over as -Dmaddi.k2.home, which the plugin passes to the
// daemon it launches -- the same directory shape as a download (a maddi-kotlin distribution's lib-k2/).
val k2Runtime: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
dependencies {
    k2Runtime("io.codelaser:maddi-kotlin-k2:$maddiVersion")
}
val k2Home = tasks.register<Sync>("k2Home") {
    description = "Gathers the K2 jars into the directory runIde hands the daemon as its Kotlin front end."
    from(k2Runtime)
    into(layout.buildDirectory.dir("k2-home"))
}

// For runIde (developing/verifying interactively): point the sandboxed IDE at the freshly built daemon
// distribution and a JDK 25+ (the Gradle daemon JVM), via the dev system-property fallbacks the plugin reads.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask> {
    dependsOn(":maddi-ide-daemon:installDist")
    val installDir = project(":maddi-ide-daemon").layout.buildDirectory.dir("install/maddi-ide-daemon")
    systemProperty("maddi.daemon.install", installDir.get().asFile.absolutePath)
    systemProperty("maddi.jdk.home", System.getProperty("java.home"))
    dependsOn(k2Home)
    systemProperty("maddi.k2.home", k2Home.get().destinationDir.absolutePath)
}

// The plugin launches the daemon distribution. Tests exercise that launch against the real install.
tasks.test {
    // the Kotlin surfaces are tested in K2 mode, the Kotlin plugin's default and the one users run
    systemProperty("idea.kotlin.plugin.use.k2", "true")
    dependsOn(":maddi-ide-daemon:installDist")
    val installDir = project(":maddi-ide-daemon").layout.buildDirectory.dir("install/maddi-ide-daemon")
    systemProperty("maddi.daemon.install", installDir.get().asFile.absolutePath)
    // The plugin test JVM runs on the IDE's JBR (21); maddi needs a JDK 25+ to run and to read java.base.
    // Pass the Gradle daemon JVM (25+) so the daemon runs on it and uses it as the analysis SDK.
    systemProperty("maddi.test.jdkHome", System.getProperty("java.home"))
    useJUnitPlatform()
}
