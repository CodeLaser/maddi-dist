# maddi IDE plugins: changes

What changed in the IntelliJ and Eclipse plugins, per release. The IntelliJ plugin's change notes on JetBrains
Marketplace are built from the section of the release being built (`maddi-intellij`, `patchPluginXml`), so keep each
section in plain bullets: `- ` items, with `**bold**` and `` `code` `` as the only markup.

## 0.9.6

- First release on JetBrains Marketplace and the Eclipse Marketplace.
- Computed annotations as inline hints, on the line above a declaration or inline, and before a parameter's
  type, where you would write them. Defaults implied by their context are hidden unless you ask for all of them.
- Gutter icons with the full set of annotations; a verdict that holds only after a mark has its own icon.
- Contracts you write are checked; a violation is marked in the code with the reasons that led to it. Near misses
  (one method away from `@Container`, …) on request.
- A tool window (IntelliJ) or view (Eclipse) with the run's progress, the findings with their reasons, sources that
  were not analysed and why, and a run log.
- The JDK 25+ the analysis runs on is picked from the IDE's own JDKs; no setting needed when one is installed.
- Results appear while the analysis is still running, and say when they are not final.
- IntelliJ: Kotlin sources too, with the Kotlin front end downloaded on demand.
- Licensed under the Apache License 2.0.
