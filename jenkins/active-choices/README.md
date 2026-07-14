# Active Choices parameter scripts

Reference copies of the Groovy scripts behind the job's **Active Choices**
parameters. They cannot be defined from `Jenkinsfile.release-git` (Active Choices
has no Pipeline DSL symbol, and a declarative `parameters {}` block would wipe
manually-added Active Choices parameters on every run). So the live copies are
pasted into the Jenkins job UI; these files exist so the setup is
version-controlled and rebuildable.

Keep these in sync with the UI by hand.

## BASE_BRANCH

An **Active Choices Parameter** that lists release-relevant branches
(`master` + `DragonFly_RELEASE_*`) from the canonical DragonFly repo. It only
populates the dropdown — the pipeline still operates against the writable
`ORIGIN_URL`.

- `base-branch.groovy` — the "Groovy Script" (choices).
- `base-branch-fallback.groovy` — the "Fallback Script".

Wiring it up:

1. Job → Configure → **This project is parameterized** → Add **Active Choices Parameter**.
2. Name: `BASE_BRANCH`. Type: Single Select.
3. Paste `base-branch.groovy` into **Groovy Script**, and
   `base-branch-fallback.groovy` into **Fallback Script**.
4. **Uncheck "Use Groovy Sandbox"** — the script runs `git ls-remote` via
   `.execute()`, which the sandbox blocks.
5. Save, then on first run approve it in
   Manage Jenkins → **In-process Script Approval**.

The pipeline rejects any `BASE_BRANCH` value starting with `ERROR`, so a failed
branch listing fails the build rather than silently defaulting to `master`.
