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

// A benchmark tool, never published: a headless IntelliJ command, `idea.sh maddi-j2k <project> <source root>`, that
// converts a module's Java sources to Kotlin with IntelliJ's own J2K, through the code path of the "Convert Java File
// to Kotlin File" action (CodeLaser/maddi-mod#36). The scoreboard of the KotlinConf proposal compares maddi's
// translation with this one. Standalone like maddi-intellij: no maddi dependency, the IDE's JBR (21).
//
// Built against a RELEASED IDE on purpose: 2026.3 EAP converts through JavaToKotlinService, whose post-processing
// analyses on the EDT and is refused (ProhibitedAnalysisException), so its output is not what users get.

plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.2.3")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
    }
}

intellijPlatform {
    projectName = "maddi-j2k"
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }
}
