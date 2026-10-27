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

// The IDE-agnostic daemon client: DTOs (AnalysisModel), the NDJSON socket client, the process launcher.
// Plain JDK + Jackson only — NO IntelliJ and NO maddi types — so any IDE front-end can share it. The
// IntelliJ plugin depends on it; an Eclipse plugin (also a JVM) can too. A VS Code extension reuses only
// the JSON wire contract (reimplemented in TypeScript). Targets Java 21, the floor of the IDE runtimes
// that consume it (IntelliJ's JBR 21, Eclipse's JRE 21+).

plugins {
    id("java-library-conventions")
    `maven-publish` // so an Eclipse/Tycho build can consume this jar from the local Maven repo (publishToMavenLocal)
}

// maddi (base) and maddi-mod modules are reached by coordinate; settings.gradle.kts includes their builds
val maddiVersion: String by project

// No version here either: the root gradle.properties supplies it. maddi-eclipse consumes this jar
// from mavenLocal and pins the coordinate in its pom (<maddi.client.version>), so the two move together.

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"]) // io.codelaser:maddi-ide-client:<project version>
        }
    }
}

dependencies {
    // exposed on the API: consumers deserialize daemon frames into AnalysisModel via Jackson (JsonNode etc.)
    api("com.fasterxml.jackson.core:jackson-databind")
}

// The launcher/round-trip tests drive the real daemon distribution (installDist: bin/ + lib/*.jar),
// exactly as an IDE front-end will. Provide its location and a JDK 25+ to run it on (the daemon needs 25).
// The K2 jars as a directory, the shape of a download: KotlinRoundTripTest hands it to a real daemon.
val k2Runtime: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
dependencies {
    k2Runtime("io.codelaser:maddi-kotlin-k2:$maddiVersion")
}
val k2Home = tasks.register<Sync>("k2Home") {
    from(k2Runtime)
    into(layout.buildDirectory.dir("k2-home"))
}

tasks.test {
    dependsOn(k2Home)
    systemProperty("maddi.test.k2Home", k2Home.get().destinationDir.absolutePath)
    dependsOn(":maddi-ide-daemon:installDist")
    val installDir = project(":maddi-ide-daemon").layout.buildDirectory.dir("install/maddi-ide-daemon")
    systemProperty("maddi.daemon.install", installDir.get().asFile.absolutePath)
    systemProperty("maddi.test.jdkHome", System.getProperty("java.home"))
}
