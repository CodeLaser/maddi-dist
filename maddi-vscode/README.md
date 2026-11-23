# maddi for VS Code

maddi analyses a whole Java project and shows what it concludes beside each declaration, written as the annotations
you would otherwise write by hand: whether a type is immutable or a container, whether a method modifies its object,
whether a parameter is changed by the call, what may be `null`, and who may hold an object. It also checks the
annotations you *did* write, and reports a contract the code breaks.

- **Hints**: the computed annotations, inline, after each type, method, field and parameter name.
- **Why**: hover over a hint for the reasons, step by step, each step a link to the statement behind it.
- **Problems**: a contract you wrote that the code breaks, in the *Problems* panel, with the reasoning.

The analysis runs in a separate process, on your machine; nothing leaves it.

## Requirements

- **Language Support for Java** (`redhat.java`), which VS Code installs with this extension. maddi asks it for the
  project model and the compiled classes, so the project must build in VS Code.
- **A JDK 25 or later**, set in `maddi.jdkHome`. maddi runs on it; it need not be the JDK your project targets, nor
  the one VS Code runs on. The first analysis offers to open the setting.

## Using it

1. Open a Java project, and wait until the Java extension has loaded it.
2. Run **Analyze with maddi** from the Command Palette. A notification shows the progress; hints appear while the
   analysis runs. A large project takes minutes.
3. **Stop maddi Analysis**, or the notification's cancel button, stops a run.

The [IDE user guide](https://github.com/CodeLaser/maddi-dist/blob/main/docs/ide-user-guide.md) explains each
annotation, which hints are shown, and the settings:

| Setting | Default | |
|---|---|---|
| `maddi.jdkHome` | none | the JDK 25+ maddi runs on |
| `maddi.hints` | `hide-implied-defaults` | which annotations appear as hints: also `all`, `positive-only`, `negative-only`, `none` |
| `maddi.warnNearMisses` | off | report types and methods that narrowly miss a property |
| `maddi.closedWorld` | off | the folder is an application: no code outside it calls its public methods |
| `maddi.incrementalReanalysis` | off | experimental: after an edit, re-analyse only what changed; such a result is never certified |
| `maddi.daemonXmxMb` | 4096 | the analysis process's heap, in MB |
| `maddi.daemonInstall` | empty | a daemon built from source, for developing maddi itself |

## Limitations

- Java only; Kotlin sources are analysed by the IntelliJ plugin.
- The JDK's and libraries' sources show no hints yet.

## More

- maddi itself, and the book behind it: [github.com/CodeLaser/maddi](https://github.com/CodeLaser/maddi).
- The same analysis in [IntelliJ IDEA](https://plugins.jetbrains.com/) (search for *maddi*) and Eclipse.
- Bugs and questions: [github.com/CodeLaser/maddi/issues](https://github.com/CodeLaser/maddi/issues).

Licensed under the Apache License, Version 2.0.
