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
// verdicts. It drives the IDE daemon through maddi-ide-client, as the IntelliJ and Eclipse plugins do, so it
// compiles against neither maddi nor maddi-mod. The distribution carries the daemon in daemon/.

plugins {
    id("java-library-conventions")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val mcpSdkVersion = "2.1.0"

dependencies {
    implementation(project(":maddi-ide-client"))
    implementation("io.modelcontextprotocol.sdk:mcp-core:$mcpSdkVersion")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson2:$mcpSdkVersion")
    // stdout is the MCP channel: the SDK's logging goes to stderr, which slf4j-simple writes to by default
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

application {
    applicationName = "maddi-mcp"
    mainClass = "io.codelaser.maddi.mcp.McpMain"
}

val daemonInstall = project(":maddi-ide-daemon").layout.buildDirectory.dir("install/maddi-ide-daemon")

distributions {
    main {
        contents {
            // found by McpMain next to lib/, unless --daemon-install says otherwise
            from(project(":maddi-ide-daemon").tasks.named("installDist")) { into("daemon") }
        }
    }
}

// The round-trip test drives the real daemon distribution on a JDK 25+, as maddi-ide-client's tests do.
tasks.test {
    dependsOn(":maddi-ide-daemon:installDist")
    systemProperty("maddi.daemon.install", daemonInstall.get().asFile.absolutePath)
    systemProperty("maddi.test.jdkHome", System.getProperty("java.home"))
}
