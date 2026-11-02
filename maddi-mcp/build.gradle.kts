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

// An MCP server (Model Context Protocol, over stdio) for coding agents: configure a project, analyse it, query the
// verdicts. It drives the IDE daemon through maddi-ide-client, as the IntelliJ and Eclipse plugins do; of maddi it
// compiles against the build-file capture alone (maddi-inspection-resource), to tell when a stored configuration
// has gone stale. The distribution carries the daemon in daemon/. Java 25, as the daemon: by default it runs on
// this process's JVM.

plugins {
    id("java-library-conventions")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

val maddiVersion: String by project

val mcpSdkVersion = "2.1.0"

dependencies {
    implementation(project(":maddi-ide-client"))
    implementation(project(":maddi-project-config")) // freshness, the captures of a build's javac invocations
    implementation("io.codelaser:maddi-inspection-resource:$maddiVersion") // BuildFilesCapture
    implementation("io.modelcontextprotocol.sdk:mcp-core:$mcpSdkVersion")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson2:$mcpSdkVersion")
    // stdout is the MCP channel: the SDK's logging goes to stderr, which slf4j-simple writes to by default
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

application {
    applicationName = "maddi-mcp"
    mainClass = "io.codelaser.maddi.mcp.McpMain"
}

// the version the server reports to the agent at initialize (McpMain.version)
tasks.jar {
    manifest { attributes("Implementation-Title" to "maddi-mcp", "Implementation-Version" to project.version) }
}

val daemonInstall = project(":maddi-ide-daemon").layout.buildDirectory.dir("install/maddi-ide-daemon")

distributions {
    main {
        contents {
            // found by McpMain next to lib/, unless --daemon-install says otherwise
            from(project(":maddi-ide-daemon").tasks.named("installDist")) {
                into("daemon")
                // the server runs daemon/bin/maddi-ide-daemon: the zip, unlike installDist, would drop its x bit
                filesMatching("bin/*") { permissions { unix("rwxr-xr-x") } }
            }
            // the zip is published on the GitHub release (release-cli.sh): the licence and what it bundles
            from(rootProject.layout.projectDirectory) { include("LICENSE", "NOTICE", "THIRD-PARTY.txt") }
        }
    }
}

// THIRD-PARTY.txt lists the libraries of the daemon (maddi-ide-daemon checks those) and of this server
val checkThirdPartyNotices = tasks.register("checkThirdPartyNotices") {
    description = "Checks that THIRD-PARTY.txt lists every third-party library the MCP server distribution bundles."
    group = "verification"
    val notices = rootProject.layout.projectDirectory.file("THIRD-PARTY.txt")
    val runtime = configurations.runtimeClasspath
    inputs.file(notices)
    inputs.files(runtime)
    doLast {
        val listed = notices.asFile.readLines().map { it.substringBefore(' ').trim() }.filter { ':' in it }.toSet()
        val bundled = runtime.get().incoming.artifacts.artifacts
            .map { it.id.componentIdentifier }
            .filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
            .filter { it.group != "io.codelaser" }
            .map { "${it.group}:${it.module}" }
            .toSortedSet()
        val missing = bundled - listed
        if (missing.isNotEmpty()) {
            throw GradleException("THIRD-PARTY.txt does not list " + missing.joinToString(", ")
                    + ": add each with its license before the MCP server ships it")
        }
    }
}
tasks.named("check") { dependsOn(checkThirdPartyNotices) }
tasks.named("distZip") { dependsOn(checkThirdPartyNotices) }

// The same distribution as an MCP bundle (MCPB: github.com/modelcontextprotocol/mcpb), for Claude Desktop and the MCP
// Registry: a zip with mcpb/manifest.json and its icons at the top, the install beside them. The bundle carries no JDK;
// the manifest asks the user for one. release-cli.sh publishes it on the GitHub release.
tasks.register<Zip>("mcpb") {
    description = "Builds the MCP server as an MCP bundle (.mcpb)."
    group = "distribution"
    archiveFileName = "maddi-mcp-${project.version}.mcpb"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from(tasks.installDist)
    val version = project.version.toString()
    inputs.property("version", version)
    from("mcpb") {
        filesMatching("manifest.json") { filter { it.replace("@VERSION@", version) } }
    }
    // the manifest runs bin/maddi-mcp through /bin/sh, but the server runs daemon/bin/maddi-ide-daemon itself
    filesMatching(listOf("bin/*", "daemon/bin/*")) { permissions { unix("rwxr-xr-x") } }
    dependsOn(checkThirdPartyNotices)
}

// The round-trip test drives the real daemon distribution on a JDK 25+, as maddi-ide-client's tests do.
tasks.test {
    dependsOn(":maddi-ide-daemon:installDist")
    systemProperty("maddi.daemon.install", daemonInstall.get().asFile.absolutePath)
    systemProperty("maddi.test.jdkHome", System.getProperty("java.home"))
}
