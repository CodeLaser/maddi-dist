# maddi in the IDE — user guide

maddi analyses a whole Java (or Kotlin) project and shows what it concludes beside each declaration, written as
the annotations you would otherwise write by hand: whether a type is immutable or a container, whether a method
modifies its object, whether a parameter is changed by the call, what may be `null`. It also checks the
annotations you *did* write, and reports a contract the code breaks. In IntelliJ it can also translate Java to
Kotlin, using those verdicts.

This guide covers the IntelliJ IDEA and Eclipse plugins. They work the same way; where they differ, each has its
own short section.

## Before you start

- **A JDK 25 or later.** The analysis runs in a separate process (the *daemon*, bundled with the plugin), and that
  process needs JDK 25+. The IDE itself can keep running on whatever it runs on. The plugin picks a JDK 25+ the
  IDE already knows about; if there is none, add one (IntelliJ: *File → Project Structure → SDKs*; Eclipse:
  *Preferences → Java → Installed JREs*) or choose one in the maddi settings.
- **A project that compiles.** maddi reads your sources and the compiled classes next to them. The plugin builds
  the project before each analysis. Files that do not parse are left out and the result says so; the rest is
  still analysed.

## Installing

| IDE | Requires | Install |
|---|---|---|
| IntelliJ IDEA | 2025.3 or later | *Settings → Plugins → Marketplace*, search for **maddi** |
| Eclipse | 2025-09 or later | *Help → Install New Software… → Add…*, location `https://codelaser.github.io/maddi-dist/eclipse/` |

## Try it on a demo project

Two small public projects show every kind of hint, one class or two per annotation, each class's documentation
saying what to look for:

- [**maddi-java-demo**](https://github.com/CodeLaser/maddi-java-demo): a Maven project in Java;
- [**maddi-kotlin-demo**](https://github.com/CodeLaser/maddi-kotlin-demo): the same classes in Kotlin, as a Gradle project (IntelliJ).

Each has an `EXPECTED-HINTS.md` listing, per declaration, what the analysis computes, so you can tell a missing
hint from a broken setup, and a package `demo.guard` whose contracts are wrong on purpose, to see a violation and
its reasoning. Clone one and open it: IntelliJ, *File → Open* on the directory; Eclipse (Java demo), *File →
Import… → Maven → Existing Maven Projects*. Then run the analysis as below.

## Your first analysis

- IntelliJ: *Tools → Analyze with maddi*. The **maddi** tool window (bottom) shows the run.
- Eclipse: *Project → Analyze with maddi*, or right-click a Java project. *Window → Show View → Other… → maddi →
  maddi Findings* shows the run.

The first run starts the daemon and loads maddi's knowledge of the JDK and common libraries; later runs reuse
the daemon. Hints appear in open editors while the analysis is still running and fill in as it goes. A large
project can take minutes: the tool window shows the phase, the pass and the elapsed time.

### Stopping a run

Any of these stops a running analysis at once:

- the **Stop** button in the maddi tool window (IntelliJ) or the maddi Findings view (Eclipse);
- *Tools → Stop maddi Analysis* (IntelliJ), *Project → Stop maddi Analysis* (Eclipse);
- the cancel button on the progress bar (IntelliJ) or in the Progress view (Eclipse).

What the run had shown so far stays in the editor. The next analysis starts from scratch.

Closing the IDE stops the daemon too, also in the middle of a run.

## Turning the hints on

### IntelliJ IDEA

Hints are on by default. If you see none after an analysis:

- *Settings → Editor → Inlay Hints → Other → Java → maddi analysis hints* must be ticked, and inlay hints must not
  be switched off altogether.
- *Settings → Tools → maddi → Inline hints* must not be *Off (gutter only)*.

### Eclipse

Eclipse draws maddi's hints as **code minings**, and code minings are **off by default** in Eclipse. Turn them on
once:

1. *Preferences → Java → Editor → Code Minings*;
2. tick **Enable code minings**;
3. reopen the editors that were already open.

Without this, the analysis runs and the ruler icons and problems appear, but no hints. *Preferences → maddi →
Show hints in the editor* must be ticked too (it is by default).

## Reading the results

maddi shows its results on three surfaces:

- **Hints**: the computed annotations, on a line of their own above each type, method and field, and before
  each parameter, where you would write them. *Hint placement* in the settings puts them after the name instead.
- **Gutter (IntelliJ) / ruler (Eclipse) icons**: on each analysed type, method and field. The tooltip has the
  full set, each parameter's included, and the reasons: under each annotation that has one, the chain of
  statements behind it, from the element to the statement that decides it (`⇐ line 9: calls store(..)`). A method,
  field or parameter that is `@Modified`, `@Dependent` or `@Nullable`, and a method with `@StaticSideEffects`, says
  why. A type below `@Immutable` says which rule it fails, at the member that fails it. A type that is not
  `@Container`, but is at most 2 parameters away from it, says so under `not @Container:`, one line per parameter
  that is modified. A verdict that holds only after a mark (see `after=` below) has an icon of its own (Eclipse: a
  ring instead of a dot).
- **Problems**: a contract you wrote that the code breaks, underlined, with the reasoning that led to it.

### What the annotations mean

| Annotation | On | Means |
|---|---|---|
| `@NotModified` / `@Modified` | method | does not / does change the object it is called on |
| `@NotModified` / `@Modified` | parameter, field | the object is not / is changed through it |
| `@Modified(hc=true)` | parameter, method | only what the object *holds* is changed (the elements of a list), not the object itself |
| `@Immutable` | type | an instance never changes, nor does anything it holds |
| `@Immutable(hc=true)` | type | as `@Immutable`, except for content it holds without knowing its type (a type parameter, an interface): that content may change |
| `@FinalFields` | type | all fields are final, yet the object is not immutable: what they point to changes |
| `@Mutable` | type | none of the above |
| `@Container` | type | its methods do not change the arguments you pass them |
| `@Independent` | method, parameter, type | no mutable state is shared with the caller: changing the result (or the argument) cannot change this object |
| `@Independent(hc=true)` / `@Dependent` | method, parameter, type | only content of unknown type is shared / mutable state is shared |
| `@UnmodifiableView` | method | the result is a read-only view of mutable state |
| `@Identity`, `@Fluent` | method | returns its first argument / returns `this` |
| `@StaticSideEffects` | method | changes static state |
| `@Nullable` / `@NotNull` | field, parameter, method | may be / is never `null` |
| `…(after="m")` | type, method | the property holds once the mark `m` has been set, e.g. after a `freeze()` |

### Which hints you see

Many verdicts are the expected default in their context, and showing them all would bury the interesting ones:
`@Mutable` on most classes, `@Modified` on a `void` setter, `@FinalFields` on an interface (it has no fields, so
"all fields final" says nothing there). By default these *context defaults* are hidden.
*Inline hints* in the settings chooses:

- **Hide context defaults** (default): the informative verdicts only;
- **Show all**: everything the analysis concluded;
- **Positive only**: the guarantees (`@NotModified`, `@Immutable`, …);
- **Negative only**: the warnings (`@Modified`, `@Mutable`, …);
- **Off (gutter only)**: no hints; the icons keep everything.

## Library code

Follow a call into the JDK or a library (`Set.add`, `List.of`) after an analysis, and the library's source shows
the hints and icons too, provided the IDE has the library's sources (the JDK's `src.zip`, a sources jar).
These verdicts come from maddi's own description of the library, its *annotated API*, and the icon's tooltip
says which kind each is:

- **Manually annotated, in maddi's annotated API**: written down by the maddi authors after reading the
  library;
- **No annotation in maddi's annotated API: @Modified is the default**: nothing was written down, so maddi
  assumes the safe answer: the method may modify;
- **Not in maddi's annotated API**: a library maddi has no description of; its verdicts are the analysis's
  defaults.

Library hints appear once an analysis has finished in this IDE session; a library file opened during a run
shows them when the run ends. A decompiled class (no sources) shows none. In Eclipse, the library's hints need
code minings on, as the project's do (see *Turning the hints on*); its ruler icons do not.

## Writing your own contracts

Annotate your code with maddi's annotations (`io.codelaser:maddi-annotation`) to state what a type or method
must be: `@Container` on an interface, `@NotModified` on a method, `@Immutable` on a type. maddi then checks every
implementation against it and reports a violation as a problem, with the reasoning. The demos' `demo.guard` package
has two such violations, and `demo.immutable.Shape` a contract on an interface that its implementation keeps.

*Warn about near misses* (off by default) also reports types and methods that narrowly miss a property, e.g. a
type that would be `@Container` but for one method. Useful on code you are cleaning up; noisy elsewhere.

## Translating Java to Kotlin (IntelliJ)

maddi translates Java to Kotlin with its own translator, which reads the analysis's verdicts: a `List` field or
result becomes Kotlin's read-only `List` where nothing modifies it and `MutableList` where something does, a type
gets `?` only where the analysis finds that `null` can occur, and a record becomes a `data class`. This is not
IntelliJ's own *Convert Java File to Kotlin File*, which has no such verdicts to go on. Right-click in the Project
view or the editor, or use the *Refactor* menu:

- **Translate to Kotlin with maddi (Preview)**, on one or more Java files: each translation opens beside its Java in
  the diff viewer. Nothing is written. A preview, because a single Kotlin file among Java ones may need interop
  details (`@JvmStatic`, getter names) the translator does not write yet.
- **Translate Module to Kotlin with maddi**, on a module: its main sources are translated together, as one Kotlin
  code base, into `build/maddi-kotlin/` of the module, with `translation-report.txt` beside them. The notification
  then offers **Replace the Java sources…**: each Java file becomes its Kotlin file, in place. The module must be set
  up to build Kotlin; Local History keeps the Java files (label *Before maddi Translate to Kotlin*).

The [Java demo](https://github.com/CodeLaser/maddi-java-demo) is a good first module to translate: its classes are small and each shows one verdict, so
the effect of the analysis on the Kotlin is easy to see; the [Kotlin demo](https://github.com/CodeLaser/maddi-kotlin-demo) holds the same classes written by
hand, to compare with.

The translation reads the last analysis. When the files changed since then, or there has been none, the project
is built and analysed first, and the translation follows when the analysis ends (stopping the analysis cancels it).
While an analysis is running the action waits: start it again when the run has finished.

The report (and, for a preview, the notification) lists what the translator could not say the same way: *INFO*
(said differently, same behaviour), *BEHAVIOUR_CHANGE* (can behave differently at run time), *LOSS* (something of
the Java's is not in the Kotlin) and *ERROR* (not translated: the file will not compile as is). Read the
*BEHAVIOUR_CHANGE* and *ERROR* lines before replacing the sources.

## Settings

IntelliJ: *Settings → Tools → maddi*. Eclipse: *Preferences → maddi*.

| Setting | Default | |
|---|---|---|
| maddi JDK | the IDE's newest JDK 25+ | the JDK the daemon runs on |
| Daemon max heap (MB) | 4096 | raise it for a large project that runs out of memory |
| Re-analyze automatically after a build | IntelliJ on, Eclipse off | Eclipse builds on every save |
| Inline hints | Hide context defaults | see *Which hints you see* |
| Hint placement | Above the declaration | or inline, after the name |
| Show gutter / ruler icons | on | |
| Mark guard contract violations | on | the problems for violated contracts |
| Warn about near misses | off | |
| Daemon install override | empty | a daemon built from source, for developing maddi itself |

IntelliJ also has the Kotlin settings below.

## Kotlin (IntelliJ)

For a project with Kotlin sources, maddi needs its Kotlin front end, a separate download (the
[Kotlin demo](https://github.com/CodeLaser/maddi-kotlin-demo) is a quick way to see it at work). The first analysis
of such a project offers it in a notification (*Download Kotlin support*); until then the Java sources are
analysed and the result says the Kotlin ones were not. *Settings → Tools → maddi → Kotlin front end override*
points at a front end you downloaded by hand.

## When something is wrong

- **No hints at all**: see *Turning the hints on*. Then check that the analysis finished: the maddi tool window
  (IntelliJ) or the maddi Findings view (Eclipse) says how the last run ended.
- **"maddi needs a JDK 25 or later"**: add one to the IDE (see *Before you start*) or choose one in the settings.
- **Results look incomplete**: some files did not parse, or the analysis stopped before it converged; the tool
  window says which. A partial result can show weaker verdicts than the code deserves.
- **The translation says "a daemon from before the translate request"**: the daemon that answered is older than
  the plugin. *Restart daemon* in the maddi tool window, then translate again.
- **The analysis fails**: the tool window has the message. The daemon's own log is next to the IDE's
  (IntelliJ: *Help → Show Log in Finder/Explorer*; Eclipse: `.metadata/.plugins/io.codelaser.maddi.eclipse/
  maddi-daemon.log` in the workspace).

Questions and bug reports: https://github.com/CodeLaser/maddi/issues.
