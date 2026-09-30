# maddi-dist — notes for AI assistants

This is one of three repositories split from maddi; **read `README.md` for the tier rule first**. The general
notes live in the base repository and apply here unchanged: `../maddi/CLAUDE.md`, `../maddi/AGENTS.md`
(commands, engine facts, working style) and `../maddi/ARCHITECTURE.md`.

The two rules that follow from the project being public hold here too: the customer behind the private proving
corpus is never named (write `closed-core` / `com.example.*`; the commit hook in `.githooks` refuses the rest),
and open work items become GitHub issues rather than new checkbox `.md` files.

Before reasoning about immutability, modification, independence, linking, or the analyzer's convergence
machinery, read `../maddi/road-to-immutability/llm-summary.md`.

Here in particular: never add a compile dependency on a maddi-mod module to reach something the analysis knows.
Add what you need to `AnalysisEngine` (maddi, `maddi-analysis-api`) and implement it in maddi-mod's
`maddi-run-analysis`. The IDE runs the daemon the plugin BUNDLES: after a change, rebuild the daemon's
`installDist` and check the installed jars, not the build output.
