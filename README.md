# maddi-dist

What users install from [maddi](https://github.com/CodeLaser/maddi): the analysis **daemon** and its client, the
**IntelliJ**, **Eclipse** and **VS Code** plugins, the **Gradle** and **Maven** plugins, and the command-line
distributions `maddi` and `maddi-kotlin` (`maddi-cli`, `maddi-cli-kotlin`, released by `release-cli.sh`). The
`dogfood/` build applies the Gradle plugin to maddi's own CST and feeds the eventual-immutability ratchet.

## The tier rule

maddi was split into three repositories (see `maddi/docs/roadmap/split-maddi-into-three-repositories.md`):

| repository | tier | depends on |
|---|---|---|
| [maddi](https://github.com/CodeLaser/maddi) | base: CST, front ends, inspection, call graph, drivers, the `AnalysisEngine` interface | nothing above it |
| [maddi-mod](https://github.com/CodeLaser/maddi-mod) | mod: the modification analysis | base only |
| **maddi-dist** | dist | **compiles against base only**; carries mod at run time |

Every module here reaches the analysis through `io.codelaser.maddi.analysis.api.AnalysisEngine`. maddi-mod
modules may appear only in `runtimeOnly` (or, in the plugins, `shadeRuntime`) configurations; the
`maddi-tier-guard` plugin (maddi's `build-logic`) fails the build when a compile class path resolves one.

## Building

Both siblings are built from source:

    ~/git/…/maddi        (base)
    ~/git/…/maddi-mod    (mod)
    ~/git/…/maddi-dist   (this repository)

    ./gradlew build
    ./gradlew :maddi-cli:installDist           # the `maddi` CLI
    ./gradlew :maddi-ide-daemon:installDist    # the daemon the IDE plugins bundle
    task --list                                # the Eclipse and VS Code front ends (Taskfile.yml)

Licence: LGPL-3.0-or-later (`COPYING`, `COPYING.LESSER`), as maddi.
