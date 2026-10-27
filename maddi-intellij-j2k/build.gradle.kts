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
