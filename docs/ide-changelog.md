# maddi IDE plugins: changes

What changed in the IntelliJ and Eclipse plugins, per release. The IntelliJ plugin's change notes on JetBrains
Marketplace are built from the section of the release being built (`maddi-intellij`, `patchPluginXml`), so keep each
section in plain bullets: `- ` items, with `**bold**` and `` `code` `` as the only markup.

## 0.9.7

- **Incremental re-analysis (experimental)**, a new setting in IntelliJ, Eclipse and VS Code, off by default. After an
  edit, the daemon re-analyses only the changed types and what depends on them, on the state the previous run left.
  Java only. Such a result is never certified; turn the setting off for a full run. Changing it restarts the daemon.
- **Ownership**: `@Fresh` methods, `@Borrowed` parameters and `@Owned` fields are shown with the other annotations,
  and the tooltip or hover says why an element is not one of them. A `@Borrowed` argument of immutable type is a
  default, hidden unless you ask for all annotations.
- **Closed world**, off by default: tell the analysis that the project is an application, so no code outside it calls
  its public methods. A per-project toggle in IntelliJ's Tools menu, the `maddi.closedWorld` setting in VS Code, a
  workspace preference in Eclipse.
- **The analysis is faster**: the engine runs fernflower, timefold and langchain4j in about half the time of 0.9.6.
- VS Code: first release on the VS Code Marketplace and Open VSX, as a preview.
- VS Code: the hover shows why each verdict holds, as the gutter and ruler do elsewhere, with links to the statements.
- VS Code: a Stop command, also the cancel button of the progress notification; a daemon that dies ends the run at
  once.
- VS Code: the workspace is sent as per-module main and test source sets, read from the Java language server.
- VS Code: hints follow their declarations through edits; Analyze saves the Java editors first, and asking during a
  run queues one more.
- VS Code: fixed, no hints on a workspace opened through a symbolic link.
- Eclipse: the plugin bundles the current client, no longer a stale one.
- IntelliJ: no deprecated or internal platform API.
- A project with many source sets needs far less memory: one copy of each jar, not one per source set.

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


