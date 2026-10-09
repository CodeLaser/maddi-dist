<!--
Thanks for contributing. maddi's CONTRIBUTING.md covers the build, the fast/slow test split, and the
rules that are easy to violate and expensive to learn; they apply here too:
https://github.com/CodeLaser/maddi/blob/devel/CONTRIBUTING.md

TARGET BRANCH: `devel`, not `main`. GitHub proposes `main` (the default branch); change "base" to
`devel` before you create the PR. `main` only moves when a release gate promotes a tested `devel`.
-->

- [ ] This PR's base branch is **`devel`**.

## What this changes, and why

<!-- One paragraph. If it fixes an issue, link it. -->

## Checks

- [ ] `./gradlew build` passes (compile + everything not tagged `slow`).
- [ ] New or changed behaviour has a test.
- [ ] Any test that parses a real-world corpus is tagged `@Tag("slow")`.
- [ ] Every commit is signed off (`git commit -s`) — the [DCO](../DCO).
