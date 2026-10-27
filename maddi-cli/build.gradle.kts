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

/*
 * The `maddi` command-line distribution (split stage 3): the openjdk driver (maddi-run-openjdk, base) together
 * with the modification analysis it finds as a service (maddi-run-analysis, mod). The driver is base and cannot
 * carry the engine, so the launcher that ships lives here, in mod, and compiles nothing of its own.
 *
 *   ./gradlew :maddi-cli:run --args="…"          ./gradlew :maddi-cli:distZip   → maddi-<version>.zip
 *
 * The Java-to-Kotlin and Java-to-C# translators come with the driver (its --translate kotlin|csharp, with the
 * analysis's nullability facts): bin/maddi --compile-log build.log --translate kotlin --translate-output out
 */
plugins {
    id("java-library-conventions")
    application
}

// maddi (base) and maddi-mod modules are reached by coordinate; settings.gradle.kts includes their builds
val maddiVersion: String by project
val maddiModVersion: String by project
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    runtimeOnly("io.codelaser:maddi-run-openjdk:$maddiVersion")   // the driver; brings the analysis-hints archive (its runtimeOnly)
    runtimeOnly("io.codelaser:maddi-run-analysis:$maddiModVersion")  // the AnalysisEngine the driver loads
}

application {
    // launcher script `bin/maddi`, distribution `maddi-<version>.zip` (see PUBLISHING.md)
    applicationName = "maddi"
    mainClass = "io.codelaser.maddi.run.openjdkmain.Main"
    applicationDefaultJvmArgs = listOf(
        "-enableassertions", "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    )
}

run {
    if (project.hasProperty("jvmArgs")) {
        application.applicationDefaultJvmArgs += (project.property("jvmArgs") as String).split("\\s+")
    }
}
