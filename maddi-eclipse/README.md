# maddi for Eclipse

Shows maddi's analysis in the Java editor: computed annotations as hints, and violations of contracts you
have written (`@Container`, `@NotModified`, `@Immutable`, `@Independent`) as problems carrying the reasoning
that led to them.

Requires **Eclipse 2025-09 or later** and a **JDK 25+** for the analysis to run on. That JDK is not the one
Eclipse itself runs on: the analyzer is bundled with the plugin and runs out of process, so Eclipse can stay
on whatever JVM it likes.

## Installing

Build the update site (see below), then in Eclipse:

**Help → Install New Software… → Add… → Local…**, select
`io.codelaser.maddi.eclipse.repository/target/repository`, tick **maddi**, and follow the wizard.

`target/io.codelaser.maddi.eclipse.repository-*.zip` is the same site archived — use *Add… → Archive…* for
an offline install, or publish it as a release asset and point *Add…* at its URL.

maddi runs on a JDK 25 or later from Eclipse's own list (*Preferences → Java → Installed JREs*): the workspace
default when it is one, else the newest. Add one there if there is none, or pick another in **Preferences → maddi**.

## Using it

Right-click a Java project, or use **Project → Analyze with maddi** (the project of the selection or of the active
editor). The project is built first, and the workspace projects it requires are analysed along with it, each with
its own class path, as an IntelliJ project's modules are. Results appear as:

- **hints** — computed annotations, on their own line above each declaration (parameters inline, where the
  annotation would be written). Needs *Preferences → Java → Editor → Code Minings → Enable code minings*, which
  gates every provider in JDT's editor, this one included. Defaults are hidden; *Inline hints: Show all* shows them.
- **ruler icons** — on each analysed type, method and field, with the full set in the tooltip. A ring instead of a
  dot marks a verdict that holds only after a mark (`@Immutable(after=...)`).
- **problems** — contract violations as errors, near misses as info, the code underlined, with the why-chain.
- **the maddi Findings view** — Window → Show View → Other… → maddi. What the run is doing (elapsed time, phase,
  which daemon build answered), the findings grouped by file with their why-chains, sources that were not analysed
  and why, and a log of the run. Its toolbar analyses the last project again, restarts the daemon (after an
  `installDist` on a daemon install override) and clears the log. A run that fails says so here, and a manual run
  also in a dialog; the daemon's own output is in the workspace's `.metadata/.plugins/io.codelaser.maddi.eclipse/
  maddi-daemon.log`.

Preferences → maddi has the same settings as the IntelliJ plugin, Kotlin aside: which inline hints to show and
where declaration hints go, the ruler icons and the problem markers, whether to re-analyse after each build (off by
default: Eclipse builds on every save), and whether to warn about near misses (off by default; noisy on uncurated
code).

## Building

A Maven/Tycho reactor, deliberately separate from the Gradle multi-project: Eclipse plugins are OSGi bundles,
built PDE-style. Two prerequisites come from the Gradle side, so build them first:

```bash
./gradlew :maddi-ide-client:jar :maddi-ide-daemon:installDist
cd maddi-eclipse && mvn clean verify
```

- `maddi-ide-client` is copied from `maddi-ide-client/build/libs` and embedded on the bundle classpath, with
  Jackson from Maven Central. Its version is `maddi.client.version` in the bundle's pom, which moves with the root
  `gradle.properties`; `release-eclipse.sh` refuses a mismatch. It is not taken from `~/.m2`: a locally published
  release version there would shadow the real one in every other build on the machine.
- the daemon `installDist` tree is copied into the bundle at `process-resources`, and the repository's `LICENSE`,
  `NOTICE` and `THIRD-PARTY.txt` into its `legal/`.

Releasing: `./release-eclipse.sh v<version>` builds all of this and publishes the update site (see the script's
header, and `PUBLISHING.md` in maddi). `DRY_RUN=1` builds and prepares the site without publishing anything.

Use **Maven 3.9**: Maven 3.10's resolver rejects the system-scoped bundle dependencies Tycho 5.0.3 hands it
("Invalid Collect Request: null -> [...]") before anything compiles.

Modules: the **bundle**, a headless **test** bundle (a real Equinox runtime with JDT, no workbench), the
**feature** (what a user installs), and the **repository** (the p2 site).

Resolution needs network access to the 2025-09 p2 release train the first time; after that Tycho caches it
under `~/.m2/repository/.cache/tycho`.

## Two things worth knowing

**The bundle must be installed unpacked.** It carries the daemon as a distribution with a launch script that
has to be a real file on disk. That is why the manifest says `Eclipse-BundleShape: dir` and the feature says
`unpack="true"`; drop either and the plugin installs as a jar and cannot start its analyzer.

**p2 does not preserve the executable bit** on that launch script, so a freshly installed plugin has a
non-executable `daemon/bin/maddi-ide-daemon`. `DaemonLauncher` restores it before launching, which is the
only reason installs work — do not remove that call.
