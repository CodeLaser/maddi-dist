/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

// maddi-dist: what users install -- the IDE daemon and client, the IntelliJ / Eclipse / VS Code plugins, the Gradle
// and Maven plugins, and the command-line distributions. It COMPILES against maddi (base) only and carries
// maddi-mod at run time; the maddi-tier-guard plugin fails a build whose compile class path says otherwise.
// Both siblings are built from source: ../maddi and ../maddi-mod.
// See ../maddi/docs/roadmap/split-maddi-into-three-repositories.md.

// Type-safe accessors aren't generated for this settings-plugin extension inside the nested
// dependencyResolutionManagement.repositories scope, so import the extension function explicitly.
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    // java-library-conventions and maddi-tier-guard, shared with maddi and maddi-mod
    includeBuild("../maddi/build-logic")
}

plugins {
    // Adds the IntelliJ Platform repositories at the settings level, required because
    // repositoriesMode is FAIL_ON_PROJECT_REPOS (no per-project repositories allowed). See maddi-intellij.
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Kotlin K2 Analysis API ('*-for-ide' artifacts) -- not on Maven Central. See maddi-kotlin-k2 in maddi.
        maven(url = "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        maven(url = "https://www.jetbrains.com/intellij-repository/releases")
        maven(url = "https://cache-redirector.jetbrains.com/intellij-third-party-dependencies")
        // IntelliJ IDEA SDK + marketplace, for the maddi-intellij plugin module.
        intellijPlatform {
            defaultRepositories()
        }
    }
}

rootProject.name = "maddi-dist"

// from source: every io.codelaser:maddi-* coordinate below resolves to a project of one of these builds
includeBuild("../maddi")
includeBuild("../maddi-mod") // named only in runtimeOnly / shadeRuntime configurations (maddi-tier-guard)

include("maddi-cli")
include("maddi-cli-kotlin")
include("maddi-ide-daemon")
include("maddi-ide-client")
include("maddi-intellij")
include("maddi-gradleplugin")
include("maddi-mvnplugin")
