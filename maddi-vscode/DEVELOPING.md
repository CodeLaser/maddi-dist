# maddi for VS Code — developer notes

How the extension works and how to build and test it. The user-facing page, which the marketplaces show, is
[`README.md`](README.md).

Third front-end after IntelliJ and Eclipse. Same architecture — the analyzer runs out of process and is
spoken to over a socket in plain JSON — with one thing that is genuinely different here.

## The problem this front-end had to solve

maddi's openjdk parser needs the project's **`.class` files** on the classpath. IntelliJ and Eclipse both
have a compiler and a project model to ask, so the other two plugins just read the compiler output
directories. VS Code has neither: it is an editor, and Java support is an extension.

The answer is that the extension it delegates to — `redhat.java` — runs **jdt.ls**, which is Eclipse JDT.
That gives us both halves at once: a real project model *and* a real compiler writing real `.class` files.
So this extension asks jdt.ls rather than reimplementing anything:

| What | How |
|---|---|
| projects | `java.project.getAll` (minus jdt.ls's synthetic catch-all project) |
| per-module source sets | `java.project.getSettings(uri, [keys])` — each project's raw class path (containers expanded, JRE left out), its output folder and `--release` |
| classpath + output dirs (fallback) | `java.project.getClasspaths(uri, {scope})` — the runtime classpath, which includes the compiler output folders |
| source roots | `java.project.listSourcePaths` |
| fresh `.class` files | `java.workspace.compile(false)` — resolves only when the build has finished |

All of it goes through `java.execute.workspaceCommand`, the bridge `redhat.java` exposes onto jdt.ls's
delegate commands.

The daemon gets the project as **module source sets**, as from the Eclipse plugin (`MaddiEclipseConfigBuilder`,
which reads the same JDT model): each project a main and a test set, with its own libraries and the main sets of
the projects it requires. Only when jdt.ls cannot describe the workspace that way — no `getSettings`, or a source
root no set covers, such as a source folder that is the project's root, which jdt.ls leaves out — does it fall back
to the flat pair of all source roots and one union class path. The output channel says which.

## In the editor

- **Findings** are diagnostics, with their why-chain as related information.
- **Computed annotations** are inline hints. The hover says what IntelliJ's gutter and Eclipse's ruler say
  (`gutterText.ts`, a port of `GutterText`): each annotation's why, step by step with links to the statements,
  why a type close to `@Container` is not one, and for a method its parameters with theirs.
- **Hints follow their declarations through edits.** IntelliJ and Eclipse find each declaration again by name
  (`owner` and `signature`, an erased signature their compilers' bindings give); jdt.ls offers no bindings here, so
  `edits.ts` moves the positions with every change VS Code reports, as a range marker does, and drops a hint whose
  declaration was deleted. Edits never saved are forgotten when their editor closes.
- **Analyze saves the Java editors first**: the daemon reads the files on disk. Asking again during a run that has
  already saved runs once more when it ends.
- **Stop maddi Analysis** (or the progress notification's cancel) kills the daemon, the one way out of a fixpoint
  iteration in progress. What the run streamed stays on screen, marked provisional; the next analysis starts a
  fresh daemon.

## Two things that fail silently, and are handled

**LightWeight mode.** `java.server.launchMode` defaults to *Hybrid*, so jdt.ls starts in a mode where
`java.execute.workspaceCommand` does not fail — it logs a warning and returns `undefined`. Query it then and
you get an empty project model and an analysis of nothing, with no error to explain it. The extension waits
for `Standard` (`onDidServerModeChange`, then `serverReady()`).

**Stale `.class` files.** jdt.ls autobuilds by default, but "autobuild is on" and "the build has finished"
are different claims. `java.workspace.compile` is awaited as a genuine barrier before the model is read.

## Requirements

- `redhat.java` (declared as an extension dependency)
- a **JDK 25+** in `maddi.jdkHome` — maddi runs on this and reads `java.base` from it. It is not the JDK the
  project targets, and not the one VS Code runs on.

## Building

```bash
npm install
npm run compile   # or: npm test
```

Debug with **Run Extension** in VS Code (F5), which opens an Extension Development Host.

The daemon distribution has to be reachable. Either build it and point `maddi.daemonInstall` at it:

```bash
./gradlew :maddi-ide-daemon:installDist   # -> maddi-ide-daemon/build/install/maddi-ide-daemon
```

or copy that tree to `maddi-vscode/daemon/`, which is where a packaged extension looks. Note the launcher
loses its executable bit inside a `.vsix` (a zip, exactly as a p2 install and a Maven copy do); the launcher
restores it before spawning.

## Tests

- `npm test` (`task vscode:test`): unit tests of the modules that do not need VS Code, on Node's test runner. The
  modules holding the logic worth testing (`analysisModel`, `configBuilder`, `daemonClient`'s framing) deliberately
  import no `vscode`, since that module only exists inside the extension host. The config-builder tests drive the
  mapping with canned jdt.ls responses in the exact shapes jdt.ls 1.59 returns: a wrong mapping produces an analysis
  of nothing rather than an error.
- `npm run uitest` (`task vscode:uitest`): the UI tests in `src/uitest`. `@vscode/test-cli` downloads a VS Code into
  `.vscode-test/`, installs `redhat.java` there, and opens a copy of `uitest-fixture/Demo`, a small Eclipse project.
  The tests run inside that VS Code: they run *Analyze with maddi*, then read the inline hints
  (`vscode.executeInlayHintProvider`) and the diagnostics, as the editor shows them, and check that the hints follow an
  edit. A second suite turns on incremental re-analysis and edits `Box`, between `Cell`, which `Box` uses, and
  `Shelf`, which uses both and is rewired: only `Box` is parsed again, `Cell` keeps its hints, `Shelf` picks up the
  change, an edit `Shelf` cannot see leaves it as it was (the early cutoff holds), and a cold run says the same.
  A window opens while they run; on Linux without a display, run them under `xvfb-run`.
  They need a JDK 25 or later for the daemon (`MADDI_JDK_HOME`, else `java_home -v 25+` on macOS, else `JAVA_HOME`)
  and a daemon distribution (`MADDI_DAEMON_INSTALL`, else the bundled `daemon/`, else `maddi-ide-daemon`'s
  `installDist`, which `task gradle-ide` builds). `VSCODE_TEST_VERSION` picks the VS Code version, `stable` by default.
  The extension exports `lastRun()` for them: how the last *Analyze* ended, with its summary line, the result's
  outcome (`INCREMENTAL`, `CERTIFIED`, …) and the daemon's progress messages.
  Rebuild the daemon after a change to it (`./gradlew :maddi-ide-daemon:installDist`): the tests run the installed one.

## What is missing

- **hints are inline only, unlike the other two front-ends** — and this is settled, not a gap to close.
  IntelliJ and Eclipse put a declaration's annotations on a line of their own above it. VS Code has no
  equivalent: an `InlayHint` is positioned at a `Position` and renders within the line, and nothing in the
  API adds one. The only route is injecting `display: block` CSS through a decoration's `textDecoration`,
  which is unsupported and version-fragile; inline was reviewed in situ and judged fine, so do not add the
  hack.
- **no gutter or findings view** — the Problems panel covers the second, and VS Code has no real analogue of
  the first; the hover carries what the gutter says elsewhere.
- **library files** — the JDK's and libraries' sources (`jdt://` editors) show no annotations yet. The daemon
  answers `libraryAnnotations`, but places them by erased signature, and jdt.ls's document symbols give only
  unerased simple names (`add(E)`).
- not in step with the other front-ends, by choice: Kotlin sources, and Translate to Kotlin.

## Packaging and publishing

`task vscode:package` (repo root) compiles the extension, copies the daemon distribution into `daemon/` and the
repository's `NOTICE` and `THIRD-PARTY.txt` beside it, and writes `maddi-vscode-<version>.vsix` with `vsce`. The
version in `package.json` must be the release's: the `.vsix` bundles that release's daemon, and the marketplaces
refuse a version they have seen before. `release-cli.sh` checks it, attaches the `.vsix` to the GitHub release, and
publishes it to the VS Code Marketplace when `VSCE_PAT` is set and to Open VSX when `OVSX_PAT` is set.

`icon.png` is the CodeLaser glyph of `maddi-intellij`'s `pluginIcon.svg`, black on a white rounded square at 256 px
(the marketplaces take no SVG icon, and a black glyph on transparency would vanish on a dark theme).
