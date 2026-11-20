Gradle plugin
=============

The primary goal of the gradle plugin is to provide the inspector and analyzer with a proper classpath,
and a set of source directories.
These cannot be set as plugin options.


Input sources
-------------

- `sourcePackages` (list of strings) — **LEGACY, avoid**:
  - which source packages to analyze.
  - If absent, all sources are analyzed.
  - When an entry ends in a dot, all sub-packages are included.
  - It dates from the hand-written parser, which could not yet process arbitrary code; the current
    front ends have no such limitation. It is moreover **fatal on any modular project** — a restriction
    puts the source roots on javac's `SOURCE_PATH`, javac then compiles `module-info.java` implicitly,
    and every cross-module reference fails with "package X does not exist". See
    `SourceSet.restrictToPackages()`.

- `testSourcePackages` (list of strings) — **LEGACY, avoid**:
  - Similar to `sourcePackages`. The difference is made only for some analyses.

- `analyzedSourcesDir` (single string):
  - where to write the results of analysis of sources
  - if absent, do not write out result of analysis
- `analyzedTestSourcesDir` (single string):
  - similar to `analyzedSourcesDir`

- `jmods` (list of strings): 
  - which Java modules to add to the classpath
  - when absent, the default modules are added. This is a very short list.

Kotlin sources
--------------

A project whose source directories hold a `.kt` file is analyzed whole, Java and Kotlin together, by the
Kotlin-capable analyzer. The Kotlin compiler cannot be published to Maven Central, so it is not in this plugin's
jar: the task runs the `maddi-kotlin` CLI distribution of the plugin's own maddi version as a child process. On
first use that distribution is downloaded from the GitHub release, checked against its published SHA-256, and
cached under `<gradle user home>/caches/maddi-kotlin`. It needs JDK 26 or later.

- `skipKotlinSources` (boolean): analyze the Java sources alone with the Java-only analyzer, and ignore the Kotlin.
- `kotlinDistribution` (single string): a local `maddi-kotlin` distribution, either unpacked (a directory holding
  `lib/` and `lib-k2/`) or its zip, used instead of the download. This is for offline builds and for maddi's
  own development.
- `kotlinJavaHome` (single string): the JDK that runs the Kotlin-capable analyzer. When absent, a Gradle toolchain
  of `kotlinJavaVersion` is used.
- `kotlinJavaVersion` (integer, default 26): the version of that toolchain.
- `kotlinJvmArgs` (single string): extra JVM options for the analyzer process, space-separated, e.g. `-Xmx8g`.

Analysis hints
--------------

Analysis hints are hand-written `.java` files that annotate a library's API surface (`@Immutable`,
`@Container`, `@NotNull`, …) for types the analyzer can't see the source of. Compiling them yields
*analysis results* (`.json`), the precomputed analysis values loaded when analyzing code that uses those
libraries.

- `preloadAnalysisResultsDirs` (list of strings):
  - Directories of pre-computed analysis results (`.json`) to load for library types (use case 1).
  - When absent, the results shipped with maddi are loaded: the JDK, JUnit Jupiter, AssertJ, the logging APIs
    and the Kotlin standard library. `none` loads nothing; `default` names the shipped results in a list
    (`default,my/results`). A run that writes results or hints (`analysisResultsTargetDir`, `updatedHintsDir`)
    loads nothing implicitly.

- `hintsPackages` (list of strings):
  - Restricts which packages of hint sources are processed (use cases 2 and 3).
  - When an entry ends in a dot, all sub-packages are included as well.

- `analysisResultsTargetDir` (single string):
  - Where to write the compiled analysis results (`.json`) when processing hint sources (use case 2).
  - Absent means the results are not written out.

- `updatedHintsDir` (single string):
  - Where to write updated analysis-hint (`.java`) files for the processed packages (use case 3).

- `updatedHintsPackage` (single string):
  - Target package for the written hint files.

  
Analysis
--------

- `incrementalAnalysis` (boolean): when `true`, build on all information already present in `analyzed(Test)SourcesDirs`
- `analysisSteps` (list of strings): 
  - describes which analysis steps to carry out. 
  - If absent, all steps are executed.


General options
---------------

- `sourceEncoding` (single string): alternative source encoding, default is UTF-8
- `jre` (single string): alternative JRE location
