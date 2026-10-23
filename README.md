# maddi-dist

What users install from [maddi](https://github.com/CodeLaser/maddi): the command-line distributions, the Gradle and
Maven plugins, and the IDE plugins with the analysis daemon behind them. maddi's README explains what maddi computes;
this one explains how to run it.

The analyzer needs **JDK 25 or later** to run. The Kotlin distribution needs **JDK 26 or later**.

## Command line

Two self-contained zips on the [GitHub release](https://github.com/CodeLaser/maddi/releases/latest). Unpack and
run: every jar is in `lib/`, and the launcher carries the JVM flags the analyzer needs.

| Zip | Launcher | Analyses |
|---|---|---|
| `maddi-<version>.zip` (`maddi-cli`) | `bin/maddi` | Java |
| `maddi-kotlin-<version>.zip` (`maddi-cli-kotlin`) | `bin/maddi-kotlin` | Java and Kotlin |

`bin/maddi-kotlin` takes the same command line as `bin/maddi` and does strictly more. On a project with no `.kt`
file, it runs the Java analyzer and behaves identically. Take the small zip for a Java-only build: the Kotlin one
carries about 85 MB of K2 compiler jars. Kotlin support ships only this way, because it depends on JetBrains
artifacts that are not on Maven Central.

An option the mixed Java/Kotlin pipeline cannot honour yet is **refused by name**, with exit code 7, never run as a
no-op. Writing analysis results, incremental analysis and the hints compiler are among those options. `--translate`
is one too: a project with Kotlin sources has no single Java program to translate. `--skip-kotlin-sources` analyses
only the Java part of a mixed project, and accepts an incomplete result.

To point the CLI at a project, capture what the build compiled and hand it over. No build-tool integration is needed:

```bash
./gradlew :your-module:compileJava --debug 2>&1 | grep 'Compiler arguments:' > build.log
maddi --compile-log build.log --analysis-steps modification --analysis-results-dir out
```

`--compile-log` also reads Maven and Ant logs. The analysis loads the JDK and library results that ship with maddi;
`--preload-analysis-results-dirs none` turns that off.

### Translation

The same command line translates the project's main sources:

```bash
maddi --compile-log build.log --translate kotlin --translate-output kotlin-src
maddi --compile-log build.log --translate csharp --translate-output csharp-src   # experimental; with a Translated.csproj
```

The modification analysis and the nullability pass run first, and their verdicts decide `T` or `T?`, where `!!` (or
`!`) is needed, and which collections are read-only. `--analysis-steps none` translates without them.
`translation-report.txt` in the output lists the printer's crashes and messages; a crash leaves that one file out and
exits non-zero.

## Gradle plugin

[`io.codelaser.maddi.analyzer`](https://plugins.gradle.org/plugin/io.codelaser.maddi.analyzer) on the Gradle Plugin
Portal:

```kotlin
plugins {
    java
    id("io.codelaser.maddi.analyzer") version "0.9.5"
}

maddi {
    analysisSteps = "modification"   // parse -> prep -> modification -> write results
}
```

```bash
./gradlew maddi-analyzer     # one result .json per package, under build/maddi
```

The plugin takes the source sets, the classpath, the language level and `--enable-preview` from the Gradle model,
and forks a worker JVM with the flags the analyzer needs. Its options are in
[`maddi-gradleplugin/README.md`](maddi-gradleplugin/README.md).

## Maven plugin

`io.codelaser:maddi-mvnplugin` on Maven Central, goal prefix `maddi`, five goals: `run`,
`write-input-configuration`, `statistics`, `write-analysis-hints` and `compile-analysis-hints`. The JVM that runs
Maven needs javac's `--add-exports` flags for the analysis goals. Set them once in `.mvn/jvm.config` or
`MAVEN_OPTS`; the [user manual](https://www.codelaser.io/maddi/manual/) lists them.

## IDE plugins (build from source)

The IntelliJ, Eclipse and VS Code plugins are not published yet. Each shows maddi's computed annotations in the
editor. All three talk to the same analysis daemon (`maddi-ide-daemon`), which runs out of process, so the IDE can stay on any
JVM.

| IDE | Module | Build | Notes |
|---|---|---|---|
| IntelliJ IDEA | `maddi-intellij` | `./gradlew :maddi-intellij:buildPlugin`, or `runIde` to try it | Java and Kotlin. The Kotlin front end is downloaded on demand from the release and checked against its SHA-256. |
| Eclipse 2025-09+ | `maddi-eclipse` | `task eclipse:build` (a p2 update site) | [`maddi-eclipse/README.md`](maddi-eclipse/README.md) |
| VS Code | `maddi-vscode` | `task vscode:package` (a `.vsix`) | Needs `redhat.java`. [`maddi-vscode/README.md`](maddi-vscode/README.md) |

`task --list` shows the other tasks, and `task doctor` checks the toolchain.

`maddi-intellij-j2k` is not a plugin. It is a headless starter that runs IntelliJ's own J2K converter on a corpus, so
that maddi's Kotlin translation can be compared with it.

## Building

Check out the three repositories side by side, as described in maddi's
[README](https://github.com/CodeLaser/maddi#building-from-source):

```bash
./gradlew build
./gradlew :maddi-cli:installDist           # the `maddi` CLI, in maddi-cli/build/install/maddi
./gradlew :maddi-ide-daemon:installDist    # the daemon the IDE plugins bundle
```

`release-cli.sh <tag>` builds both zips and publishes them, with their checksums, on the GitHub release for that tag.

`dogfood/` is a standalone build that applies the Gradle plugin to maddi's own CST. It is the proving ground for
eventual immutability; see [`dogfood/README.md`](dogfood/README.md).

## Three repositories

| Repository | Holds | Depends on |
|---|---|---|
| [maddi](https://github.com/CodeLaser/maddi) | the base: CST, front ends, printers, the `AnalysisEngine` interface | nothing above it |
| [maddi-mod](https://github.com/CodeLaser/maddi-mod) | the analyses | the base only |
| **maddi-dist** | what users install | **compiles against the base only**; carries maddi-mod at run time |

Every module here reaches the analyses through `io.codelaser.maddi.analysis.api.AnalysisEngine`. maddi-mod modules
may appear only in `runtimeOnly` configurations, or `shadeRuntime` in the plugins. The `maddi-tier-guard` plugin, in
maddi's `build-logic`, fails the build when a compile class path resolves one.

Licence: LGPL-3.0-or-later (`COPYING`, `COPYING.LESSER`), as maddi.
