# maddi IDE plugins: changes

What changed in the IntelliJ and Eclipse plugins, per release. The IntelliJ plugin's change notes on JetBrains
Marketplace are built from the section of the release being built (`maddi-intellij`, `patchPluginXml`), so keep each
section in plain bullets: `- ` items, with `**bold**` and `` `code` `` as the only markup.

## 0.9.7

- **Incremental re-analysis (experimental)**, a new setting in IntelliJ, Eclipse and VS Code, off by default. After an
  edit, the daemon re-analyses only the changed types and what depends on them, on the state the previous run left.
  Java only. Such a result is never certified; turn the setting off for a full run. Changing it restarts the daemon.

## 0.9.6.1

- IntelliJ: the hints refresh after a run through public platform API only. The analysis and the daemon are those of
  0.9.6.

## 0.9.6

- First release on JetBrains Marketplace and the Eclipse Marketplace.
- Computed annotations as inline hints, on the line above a declaration or inline, and before a parameter's
  type, where you would write them. Defaults implied by their context are hidden unless you ask for all of them.
- Gutter icons with the full set of annotations; a verdict that holds only after a mark has its own icon.
- The gutter tooltip says why: the chain of statements behind a `@Modified`, `@Dependent`, `@Nullable` or
  `@StaticSideEffects`; for a type below `@Immutable`, the rule it fails at the member that fails it; for a type at
  most 2 parameters away from `@Container`, each parameter that is modified, under `not @Container:`.
- Contracts you write are checked; a violation is marked in the code with the reasons that led to it. Near misses
  (one method away from `@Container`, …) on request.
- A tool window (IntelliJ) or view (Eclipse) with the run's progress, the findings with their reasons, sources that
  were not analysed and why, and a run log.
- The JDK 25+ the analysis runs on is picked from the IDE's own JDKs; no setting needed when one is installed.
- Results appear while the analysis is still running, and say when they are not final.
- IntelliJ: Kotlin sources too, with the Kotlin front end downloaded on demand.
- Licensed under the Apache License 2.0.


