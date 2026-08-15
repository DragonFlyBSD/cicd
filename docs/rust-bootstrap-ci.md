# rust-bootstrap-dragonfly CI

Builds and publishes DragonFly Rust bootstraps for
[`DragonFlyBSD/rust-bootstrap-dragonfly`](https://github.com/DragonFlyBSD/rust-bootstrap-dragonfly)
in Jenkins, **without adding any file to that repo**. The pipeline lives here in
`cicd` (`jenkins/Jenkinsfile.rust-bootstrap-ci`).

Two modes, keyed off the branch:

- **PR** — build the changed per-version dir(s) to verify they compile; report
  pass/fail back onto the PR. Never publishes.
- **master** — build the changed version(s) and rsync-publish their
  `cargo`/`rustc`/`rust-std` bootstrap tarballs (+`.sha256`) to the archive. A
  merge to master is an accepted version, so it publishes.

Each `build.sh` is a multi-hour Rust+LLVM bootstrap, so only the version dir(s)
that changed (vs the PR base, or vs the previous master commit) are built.

## How it works

- A **Multibranch Pipeline** job tracks the repo via the **GitHub Branch
  Source**, **webhook-driven** (a `pull_request`/`push` event builds just that
  ref; periodic scanning is off). The PR commit-status check comes from the
  branch source.
- The **Remote Jenkinsfile Provider** plugin
  ([`remote-file`](https://plugins.jenkins.io/remote-file)) sources the pipeline
  text from **this `cicd` repo**, so the rust repo stays untouched.
- Because of that, the pipeline's `scm` is bound to `cicd`, not the rust repo —
  it sets `skipDefaultCheckout(true)` and checks out the rust source explicitly
  (`refs/pull/<id>/head` for PRs, which covers forks). Do not use `checkout scm`
  here; it would give the `cicd` tree.

## Publishing / the bootstrap chain

Each version bootstraps from the previous one — `1.87.0`'s `build.sh` downloads
the `1.86.0` DragonFly tarballs (`BOOTSTRAP_URL` in the rust repo's
`common.sh`). So a version's artifacts must be published before the next version
can build. Publishing happens automatically when a version dir lands on master;
to (re)publish an existing version, push a commit touching its dir to master.

`common.sh` fetches bootstraps from the legacy archive first, then the new one
(`avalon.dragonflybsd.org/misc/distfiles/rust-bootstrap`), so older versions keep
resolving from the legacy location during the migration.

### Publish credentials (folder-scoped, not committed)

The publish target/login are **folder-scoped Jenkins credentials**; only the
ids are in the Jenkinsfile. Add them on the job (**Credentials** → this folder):

| Credential id | Kind | Value |
|---|---|---|
| `rust-bootstrap-rsync-dest` | Secret text | rsync-daemon dest `host/module`, no scheme/user — the job builds `rsync://${RSYNC_USER}@${dest}/${version}/`. |
| `rust-upload` | Username with password | rsync daemon login (password read via `RSYNC_PASSWORD`). |

## One-time Jenkins setup

Plugins: **GitHub Branch Source**, **Remote Jenkinsfile Provider**. A GitHub
credential (App or PAT) that can read the repo and write commit statuses.

1. **New Item → Multibranch Pipeline**.
2. **Branch Sources → GitHub:** the repo + GitHub credential. Discover **pull
   requests** (for verify) **and branches** (so `master` builds and publishes).
   Fork PRs run untrusted code — gate to trusted authors if that matters.
3. **Build Configuration → Mode:** `by Remote Jenkins File Plugin`; **Script
   Path** `jenkins/Jenkinsfile.rust-bootstrap-ci`; **SCM**
   `https://github.com/DragonFlyBSD/cicd.git`, `master`.
4. **Scan Repository Triggers:** uncheck periodic (webhook-driven).
5. Add a GitHub webhook to `<JENKINS_URL>/github-webhook/` (Pull requests +
   Pushes).

## Knobs

Top of `jenkins/Jenkinsfile.rust-bootstrap-ci`: `NODE_LABEL` (agent label),
`BUILD_PKGS` (packages installed before building), the two credential ids. Build
timeout is `timeout(time: 8, unit: 'HOURS')` in `options`.
