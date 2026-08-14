# rust-bootstrap-dragonfly PR CI

CI wiring that builds pull requests to
[`DragonFlyBSD/rust-bootstrap-dragonfly`](https://github.com/DragonFlyBSD/rust-bootstrap-dragonfly)
in Jenkins and reports pass/fail back onto the PR — **without adding any file to
that repo**. The same job also publishes a version's DragonFly bootstrap
artifacts on demand, so the bootstrap chain can advance. The pipeline definition
lives here in `cicd` (`jenkins/Jenkinsfile.rust-bootstrap-ci`).

The one job has two modes:

- **Verify (default):** a PR/branch build diffs against the base branch, builds
  the changed per-version dir(s), reports status. Never publishes.
- **Publish (manual):** on the `master` branch, *Build with Parameters* with
  `PUBLISH_VERSION` set (e.g. `1.86.0`) rebuilds that version and rsync-publishes
  its `cargo`/`rustc`/`rust-std` bootstrap tarballs. See
  [Publishing a bootstrap](#publishing-a-bootstrap) below.

## How it works

- A Jenkins **Multibranch Pipeline** job tracks `rust-bootstrap-dragonfly` pull
  requests using the **GitHub Branch Source**. It is **webhook-driven**: GitHub
  POSTs a `pull_request` event (opened / synchronize) and the branch source
  builds *that specific PR* from the event — no polling, no full re-scan. The
  commit-status check shown on the PR (pending → ✓ / ✗) comes from the branch
  source, exactly as with a normal in-repo `Jenkinsfile`. Periodic scanning is
  the fallback and is turned **off** (see setup); the webhook is the trigger.
- The **Remote Jenkinsfile Provider** plugin
  ([`remote-file`](https://plugins.jenkins.io/remote-file)) makes the job take
  its pipeline text from **this `cicd` repo** instead of from the scanned repo.
  So `rust-bootstrap-dragonfly` stays untouched and `cicd` is the single source
  of truth for the CI logic.
- When a PR build runs, the pipeline **explicitly checks out the PR's
  `rust-bootstrap-dragonfly` source** (via `refs/pull/<id>/head`, which covers
  fork PRs too), diffs it against the base branch to find which per-version
  directory changed, and runs that version's `build.sh` end-to-end (a full
  Rust + LLVM bootstrap). It does *not* use the implicit `checkout scm` — see
  the caveat below.

### Why only the changed version is built

The repo has one directory per Rust version (`1.28.0/` … `1.86.0/`), and each
`build.sh` is a multi-hour bootstrap compile. Building all of them on every PR
is infeasible, so the pipeline builds only the version dir(s) the PR touched. A
typical "Add Rust X.Y.Z bootstrap" PR changes exactly one dir.

If a PR changes only shared files (`common.sh`, `checksums.sh`, `bin/`) and no
version dir, the job passes with a note — it does not auto-build all versions.

## Publishing a bootstrap

Each version bootstraps from the previous one: `1.87.0`'s `build.sh` downloads
the `1.86.0` DragonFly `cargo`/`rustc`/`rust-std` tarballs (see `BOOTSTRAP_URL`
in the rust repo's `common.sh`). So before a new version can be added, the
previous version's artifacts must exist at that download location. They are
produced by **rebuilding the previous version and publishing its output**.

To publish version `X`:

1. On the multibranch job, open the **`master`** branch and click **Build with
   Parameters**.
2. Set **`PUBLISH_VERSION`** to `X` (e.g. `1.86.0`) and build.
3. The job rebuilds `X` (bootstrapping from `X-1`, which must already be
   published) and rsync-publishes `cargo/rustc/rust-std-X-x86_64-unknown-dragonfly.tar.xz`
   plus `.sha256` to the archive, creating the per-version dir (`--mkpath`).
4. At the end it prints ready-to-paste `SHA256_..._tar_xz=` lines. **Add those to
   `checksums.sh` in the rust repo** so `X+1` can bootstrap from `X`.

Publishing is **refused on PR builds** (two guards) — a PR is untrusted,
unmerged code. Only a non-PR (`master`) run with `PUBLISH_VERSION` publishes.

### Publish configuration (folder-scoped credentials, not committed)

A Multibranch Pipeline has no per-job environment-variable field, so the publish
config is carried as **folder-scoped credentials** added on the Jenkins job.
Only the credential **ids** are hardcoded in the Jenkinsfile (ids are not
sensitive — `release-git` hardcodes one too); the values live in Jenkins, scoped
to the folder, so different folders can supply different values. A publish run
**fails fast** (before the multi-hour build) if either id is missing.

Add these two credentials on the job (**Credentials** → this folder):

| Credential id | Kind | Value |
|---|---|---|
| `rust-bootstrap-rsync-dest` | Secret text | rsync-daemon dest `host/module[/subpath]`, no scheme/user — e.g. `avalon.dragonflybsd.org/rust`. The job builds `rsync://${RSYNC_USER}@${dest}/${version}/`. |
| `rust-upload` | Username with password | rsync daemon login (password consumed via `RSYNC_PASSWORD`, mirroring `dragonflybsd-complete`). |

### Migrating the bootstrap archive

Old bootstraps (≤ `1.85.1`) currently live at the legacy `BOOTSTRAP_URL`; newly
published versions go to the new dest above. During the transition **both must
resolve** — keep `common.sh`'s `BOOTSTRAP_URL` on the legacy location until the
new archive holds the versions you need, then flip it (or make it try new, fall
back to old). That `BOOTSTRAP_URL` edit is a one-liner in the rust repo.

## One-time Jenkins setup

Prerequisites (install/verify under **Manage Jenkins → Plugins**):

- **GitHub Branch Source** (PR discovery + status reporting)
- **Remote Jenkinsfile Provider** (`remote-file`) — Jenkinsfile from another repo
- A GitHub credential (PAT or GitHub App) with access to the repo, able to write
  commit statuses.

Create the job:

1. **New Item → Multibranch Pipeline** (e.g. `rust-bootstrap-dragonfly-pr`).
2. **Branch Sources → Add source → GitHub**:
   - Credentials: the GitHub credential above.
   - Repository: `https://github.com/DragonFlyBSD/rust-bootstrap-dragonfly`.
   - Behaviours: keep **Discover pull requests from origin** (and from forks if
     external contributors should be built — note forked-PR builds run
     untrusted code; gate with "trusted authors only" if that's a concern).
3. **Build Configuration**:
   - **Mode:** `by Remote Jenkins File Plugin`
   - **Script Path:** `jenkins/Jenkinsfile.rust-bootstrap-ci`
   - **Remote Jenkinsfile SCM:** Git →
     `https://github.com/DragonFlyBSD/cicd.git`, branch `master`
     (add read credentials if the cicd repo is not public).
4. **Scan Repository Triggers:** **uncheck** "Periodically if not otherwise
   run" — the build is webhook-driven, not polled. (Leave it on only as a safety
   net if webhook delivery is unreliable.)
5. **Webhook (the trigger):** add a GitHub webhook on
   `rust-bootstrap-dragonfly` pointing at `<JENKINS_URL>/github-webhook/`, with
   at least the **Pull requests** and **Pushes** events. With the webhook in
   place, a PR opened or updated triggers a build of just that PR immediately;
   GitHub Branch Source handles the event granularly rather than re-scanning.

That's it — open or push to a PR and the check appears on it automatically. No
periodic scanning runs.

## Adjusting the pipeline

Both knobs are near the top of `jenkins/Jenkinsfile.rust-bootstrap-ci`:

- `NODE_LABEL` — agent label of the DragonFly build node(s). Defaults to
  `'dragonflybsd-nvmm large'` to match `Jenkinsfile.release-git`; confirm it
  matches your workers.
- `BUILD_TIMEOUT_HOURS` — hard cap on a single build (default 8).

## Notes / caveats

- **`checkout scm` points at `cicd`, not the rust repo.** Under Remote
  Jenkinsfile Provider the pipeline's `scm` variable is bound to the repo the
  Jenkinsfile came from (`cicd`), because the plugin wraps a
  `CpsScmFlowDefinition` around the remote SCM. So the pipeline sets
  `skipDefaultCheckout(true)` and checks out `rust-bootstrap-dragonfly`
  explicitly (PR head via `refs/pull/<id>/head`). Do **not** rely on
  `checkout scm` here — it would give you the `cicd` tree and the version diff
  would always come up empty.
- **Build dependencies** (llvm, gcc7, python, cmake, libssh, perl) must be
  present on the build node — see the rust repo's README. Worker provisioning
  is out of scope here.
- **Editing the pipeline** is a change to this `cicd` repo, reviewable
  independently of the rust sources it builds.
