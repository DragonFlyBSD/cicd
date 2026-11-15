# Active Choices parameter scripts

Reference copies of the Groovy scripts behind the job's **Active Choices**
parameters. The pipeline leaves `BASE_BRANCH` and `ORIGIN_URL` configuration in
the Jenkins job UI and defines the five standard release parameters in its
declarative `parameters {}` block. Declarative preserves manually configured
parameters whose names it has not previously managed. Keep `BASE_BRANCH` out of
that block and avoid replacing the parameter property with `properties(...)`.
The live branch scripts are pasted into the job UI; these files keep that setup
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
4. **Uncheck "Use Groovy Sandbox" for the main script** — it launches Git on
   the controller. Keep the fallback script sandboxed.
5. Save, then approve the exact main script under
   Manage Jenkins → **In-process Script Approval**. Changes to the main script
   require saving and approving it again. Prefer approval of the reviewed whole
   script over globally approving process-execution method signatures. The main
   script runs with controller privileges, so restrict who can edit the job.

The pipeline rejects any `BASE_BRANCH` value starting with `ERROR`, so a failed
branch listing fails the build rather than silently defaulting to `master`.

The controller needs Git installed and network access to
`git://git.dragonflybsd.org/dragonfly.git`. The main script drains process output
while Git runs, limits execution to 15 seconds, and preserves the branch order
returned by Git (it does not sort releases numerically).

Troubleshooting:

- `ERROR: git failed or timed out`: check controller Git availability and network
  access to the repository.
- `ERROR: <exception>`: the main script caught an execution or parsing failure.
- `ERROR: branch listing unavailable`: Active Choices invoked the fallback;
  check compilation errors and approval of the exact saved main script. Ensure
  the entire file, including its final closing brace, was pasted.
