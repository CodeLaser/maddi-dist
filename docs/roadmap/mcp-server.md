# The MCP server — how it is built, and what comes next

`maddi-mcp` lets a coding agent configure a project, analyse it and ask for the verdicts; the README's
[Coding agents (MCP)](../../README.md#coding-agents-mcp) section is the user's view and lists the tools. This note
records why it is built the way it is, and plans the next step: **predicting what an edit does to the verdicts**
before it is made.

## How it is built: a front-end of the IDE daemon

The server is a third front-end of the daemon the IntelliJ and Eclipse plugins use (`maddi-ide-daemon`), and talks
to it the same way: `MaddiDaemonProcess` from `maddi-ide-client`, NDJSON over a loopback socket, the daemon a
separate JVM bundled in the server's `daemon/` directory. The server itself is small and has no compile dependency on
the analysis (maddi-mod), as the tier rule requires; everything it knows of a run arrives as the daemon's
`AnalysisModel.Result`.

Why not put MCP inside the daemon instead:

- **The daemon serves one request at a time.** While an analysis runs, its connection thread is inside
  `handleAnalyze` and reads nothing else ([ide-todo.md §2](ide-todo.md)). An agent must be able to ask how a run
  is doing, query the values streamed so far, and stop it. The server keeps the answer side: it holds the run's
  last status frame and the `partialResult` frames merged so far, and answers from those while the daemon works.
  A stop is `MaddiDaemonProcess.kill()`; the next run starts a fresh daemon.
- **A run takes minutes on a large project**, longer than a tool call should block. So `analyze` is job-shaped (a
  run id, `run_status`, `stop_run`), and progress goes out as MCP progress notifications.
- **What outlives a daemon lives in the server**: the stored configurations with their build-file hashes, the
  last runs per project, the per-project daemon JDK. The daemon keeps one model, of the last run, and a different
  JDK means a different daemon process.
- **One behaviour for every front-end.** What the IDE shows, the agent gets: the same verdicts, the same
  explanations (`whys`), the same findings. A front-end that wants more asks the daemon for it in the request
  (`containerWhyMaxSlots`: the IDEs explain a type that is not `@Container` only when at most 2 parameters stand
  in the way; the server asks for all), rather than computing it itself.

The daemon also does work only it can do for the server: `writeInputConfiguration` turns a Gradle compile log
into an input configuration with the CLI's own code (`maddi-run-openjdk`), which the server cannot link against.

## What comes next: "if I made this change, what would it do?"

The question an agent has before an edit, not after it:

- **What happens to the verdicts?** Does `Bag` stay `@Container`, does `Shape` stop being `@Immutable`, does a
  method become `@Modified`, and through which statement?
- **Will I hit a guard?** A contract the user wrote (`@Immutable`, `@Container`, `@NotModified`, ...) that the edited
  code would break: a `contract-violation` finding, with the statements that cause it. And the reverse: a
  contract the edit would fix, or a near miss it would turn into a verdict.

Today this can be done by hand: analyse, edit, analyse again, `diff_runs`. The plan is to make it one question, in
two steps that share their interface.

### The tool

`what_if(project, edits)`: `edits` are file contents (or a unified diff) that replace files of the project for this
run only. The answer is the difference with the base run, as `diff_runs` gives it, ordered for the question:

1. contracts the edit would break (new `contract-violation` findings), with their why-chains, first;
2. verdicts that weaken, then verdicts that strengthen, by element;
3. contracts it would fix, and near misses that appear or go;

each with the certainty of both runs: a difference between a `FINAL` and a `BEST_AVAILABLE` run may be the run's
doing, not the edit's, and must say so.

The working tree is not touched: the agent asks before it writes.

### Step 1: a full run on the edited sources

The daemon parses the edited contents in place of the files on disk: an overlay in the analyze request, a map from
file to content. No copy of the project, no build directory touched. The IDEs can use the same overlay for unsaved
editors later.

What to watch:

- **Stale class files.** A configuration may put a module's compiled output on another module's class path. An
  edit that changes a signature then disagrees with those class files. Sources analysed together are not
  affected; a class path entry built from the project itself is, and the answer must say which ones it relied on.
- **Cost.** A full run per question costs as much as any run: minutes on a large project. Good enough to start
  with, and the reference the incremental step is tested against.
- **The base run must match.** Compare with a run of the same configuration and scope, and on unchanged sources
  (the stored run's `sourcesChangedSince` says whether it is).

### Step 2: only what the edit can reach

Re-analyse the changed types and what depends on them, keeping the rest of the base run's model. This is the
rewiring machinery: [rewiring.md](https://github.com/CodeLaser/maddi/blob/main/docs/design/rewiring.md) (the
parser keeps unchanged types as the same objects and rebuilds the rest) and
[analysis-rewiring.md](https://github.com/CodeLaser/maddi-mod/blob/main/docs/design/analysis-rewiring.md) (skip
recomputing analysis results that cannot have changed). Neither is wired into production yet. The daemon would
keep its model between requests, which it does not do today ([ide-todo.md §1](ide-todo.md), the same work the IDEs
need for partial re-analysis).

The trap: **the set of types to re-analyse is not just the dependents of the edited type.** Verdicts also flow up the
hierarchy. If `Circle.area()` starts counting its calls, the abstract `Shape.area()` becomes `@Modified`, because
an implementation is, and `Shape` stops being `@Immutable`. `Shape` does not depend on `Circle`; `Circle` depends on
`Shape`. A cone computed from dependents alone misses it, and step 2 would then predict "no change" where step 1
predicts a broken `@Immutable` contract. Step 1 run against step 2 on the same edits is the test that catches it.
