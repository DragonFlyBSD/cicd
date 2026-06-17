# DragonFly Release Pipeline Plan

This document lives in the `DragonFlyBSD/cicd` repo and describes the design of
the release Git workflow. The pipeline itself is `jenkins/Jenkinsfile.release-git`
(derived from the original release-rehearsal pipeline). For now it remains an
**inline** Jenkins Pipeline whose parameters are configured in the Jenkins job
UI, not loaded from SCM. Signing is covered separately in `docs/signing.md`.

## Goal

Create a guarded Jenkins pipeline that automates the DragonFlyBSD release process against a safe cloned remote Git repository. The first version should exercise the full release workflow without touching the real production repository.

## Core Decisions

- Use one Git remote only: `origin`.
- `origin` defaults to the cloned/test DragonFly repository.
- Default `origin` URL: `https://git.example.net/<you>/DragonFlyBSD`.
- Effective clone URL: `https://git.example.net/<you>/DragonFlyBSD.git`.
- `ORIGIN_URL` is configurable at build time.
- No `upstream` remote.
- No rehearsal/production mode split in the first version.
- All Git operations happen against `origin` only.
- The default behavior is safe: `DRY_RUN=true`.
- Human approvals are required before important write, tag, build, and publish actions.
- Version bumping should be automated, but generated values and diffs must be reviewed before committing.
- Release stage should be explicit: release candidate or final.
- First implementation scope is Git workflow only: clone, compute version, prepare edits, generate diffs, collect approvals, commit, push branches, create signed tag, and push tag.
- First implementation does not build artifacts.

## Mental Model

This is not a staging-versus-production pipeline.

It is a complete DragonFly-style release pipeline where the configured `origin` determines where it operates. For the first implementation, `origin` is a cloned/test repository that is safe to mutate.

## Jenkins Parameters

```groovy
parameters {
    string(
        name: 'ORIGIN_URL',
        defaultValue: 'https://git.example.net/<you>/DragonFlyBSD.git',
        description: 'Writable cloned DragonFly repo used as origin'
    )

    string(
        name: 'BASE_BRANCH',
        defaultValue: 'master',
        description: 'Base branch to release from'
    )

    choice(
        name: 'VERSION_BUMP',
        choices: ['major', 'minor', 'patch', 'none'],
        description: 'How to calculate target version'
    )

    string(
        name: 'TARGET_VERSION',
        defaultValue: '',
        description: 'Optional override, e.g. 6.6.0'
    )

    choice(
        name: 'RELEASE_STAGE',
        choices: ['rc', 'final'],
        description: 'Create a release candidate or final release'
    )

    string(
        name: 'RC_NUMBER',
        defaultValue: '1',
        description: 'Used only when RELEASE_STAGE=rc'
    )

    booleanParam(
        name: 'DRY_RUN',
        defaultValue: true,
        description: 'Do not push branches, commits, tags, or publish artifacts'
    )
}
```

Additional fixed defaults for the first Jenkinsfile:

```text
Jenkins agent label: dragonflybsd-nvmm large
Jenkins credential ID: release-rehersal
Jenkins GPG private key credential ID: release-rehersal-gpg-private-key
Jenkins GPG passphrase credential ID: release-rehersal-gpg-passphrase
Git commit author: configured in the Jenkins job (GIT_AUTHOR_NAME / GIT_AUTHOR_EMAIL)
Allowed approver: tuxillo
Required approvals: 1 Jenkins user
Artifact behavior: archive in Jenkins only, no copy/publish
Tag behavior: always signed tags, never fall back to unsigned tags
Clone behavior: use shallow clone with minimal depth where possible
Branch selection: configure BASE_BRANCH in Jenkins UI as an Active Choices Parameter
```

Agent package requirements:

```text
git must already be available.
gpg is required only for non-dry-run signed tags.
On DragonFly agents, install missing gpg with: pkg install -y gnupg
```

## Jenkins Job Setup

The first implementation should remain an inline Jenkins Pipeline script. Do not require committing the Jenkinsfile to the DragonFlyBSD test repository.

Because the job remains inline and the Jenkinsfile should not be committed to the repository, configure parameters in the Jenkins job UI instead of defining them from the Jenkinsfile with `properties(...)`.

The Jenkinsfile must not overwrite job parameters. The job UI is the source of truth for parameters.

Configure `BASE_BRANCH` in Jenkins UI as an Active Choices Parameter.

The Active Choices script lists branches with:

```text
git ls-remote --heads git://git.dragonflybsd.org/dragonfly.git
```

This is only for populating the dropdown. Release Git operations still clone, commit, tag, and push only to the configured writable `ORIGIN_URL`:

```text
https://git.example.net/<you>/DragonFlyBSD.git
```

The first use may require Jenkins Script Approval because the Active Choices script runs `git ls-remote` from Groovy.

There is intentionally no fallback branch list. Active Choices exposes a fallback script field in the Jenkins UI and requires it to return a valid String/List/Map. The configured fallback script must return only an `ERROR:` sentinel value and must not return `master` or any hardcoded branch list. The pipeline validation rejects any `BASE_BRANCH` value that starts with `ERROR:`.

The Git Parameter Plugin was evaluated, but it normally needs Jenkins SCM context. That would require `Pipeline script from SCM` and a Jenkinsfile committed to a repo, which is not desired for this first version.

If this becomes too brittle, use a separate dedicated pipeline repository later.

Desired branch choices would be:

```text
master
DragonFly_RELEASE_6_4
DragonFly_RELEASE_6_2
```

The pipeline normalizes values returned by the plugin, so these are treated equivalently:

```text
master
origin/master
refs/heads/master
```

## Version Logic

The pipeline should read the current version from the cloned source, then compute the target version.

Example current version:

```text
6.4.0
```

Auto-bump behavior:

```text
VERSION_BUMP=major -> 7.0.0
VERSION_BUMP=minor -> 6.6.0
VERSION_BUMP=patch -> 6.4.1
VERSION_BUMP=none  -> 6.4.0
```

DragonFly minor releases appear to use even minor numbers, so `minor` should bump by `2`:

```text
6.4.0 -> 6.6.0
```

If `TARGET_VERSION` is provided, it wins, but must be validated.

Validation examples:

```text
VERSION_BUMP=minor + TARGET_VERSION=6.6.0 -> valid
VERSION_BUMP=minor + TARGET_VERSION=6.5.0 -> reject or require explicit override
VERSION_BUMP=patch + TARGET_VERSION=6.4.1 -> valid
```

## Derived Values

Given:

```text
TARGET_VERSION=6.6.0
RELEASE_STAGE=rc
RC_NUMBER=1
```

Derive:

```text
release branch = DragonFly_RELEASE_6_6
release tag    = v6.6.0rc1
release string = RELEASE_6_6
```

Given:

```text
TARGET_VERSION=6.6.0
RELEASE_STAGE=final
```

Derive:

```text
release branch = DragonFly_RELEASE_6_6
release tag    = v6.6.0
release string = RELEASE_6_6
```

Given patch release:

```text
TARGET_VERSION=6.6.1
```

Derive:

```text
release branch = DragonFly_RELEASE_6_6
release tag    = v6.6.1
```

## Git Workflow

The pipeline clones only from `origin`.

```text
1. Clone ORIGIN_URL.
2. Verify only origin is configured.
3. Verify origin URL matches the allowed cloned/test repo pattern.
4. Fetch branches and tags.
5. Checkout BASE_BRANCH.
6. Compute target version and derived names.
7. Create or checkout release branch.
8. Apply release branch changes.
9. Apply master/base branch changes if needed.
10. Generate diffs.
11. Wait for approval.
12. Commit changes.
13. Push branches to origin.
14. Wait for approval.
15. Create tag.
16. Push tag to origin.
17. Stop after pushing the signed tag for the first implementation.
```

## Branch Rules

For new major/minor releases:

```text
Create DragonFly_RELEASE_x_y from BASE_BRANCH.
Update release branch files.
Update BASE_BRANCH/master files for next development version.
Push both branches to origin after approval.
```

For patch releases:

```text
Use existing DragonFly_RELEASE_x_y branch.
First implementation does not edit source files for patch releases.
Create and push only the signed patch tag after approval.
The checked-out `src` worktree is already on the release branch and is reused directly.
Do not fetch into the checked-out branch.
Do not create a second `release-src` worktree for patch releases.
```

For RC releases:

```text
Tag release branch as vx.y.zrcN.
```

For final releases:

```text
Tag release branch as vx.y.z.
```

## Files To Modify

For a new major/minor release, release branch changes:

```text
sys/sys/param.h
sys/conf/newvers.sh
etc/Makefile.usr
```

Master/base branch changes:

```text
sys/sys/param.h
sys/conf/newvers.sh
```

Possibly also:

```text
etc/Makefile.usr
```

if dports/pkg defaults need changing.

For patch releases in the first implementation:

```text
No source-file edits.
Tag the existing release branch only.
```

## Approval Gates

Even when using the cloned repo, approvals should stay because they validate the real process.

The first implementation uses one approval gate with one approver, not multiple separate gates.

The single gate happens after computed release values and generated diffs are available, but before any commit, push, or tag creation.

Recommended gate behavior:

```text
Approval gate: Confirm release plan, generated diffs, branch push, signed tag creation, and tag push.
Approval must be from: tuxillo.
Jenkins administrators may still be able to approve due to Jenkins permission overrides.
```

The approval screen should show:

```text
Origin URL
Base branch
Current version
Target version
Version bump type
Release stage
Release branch
Release tag
Dry-run status
Files changed
Approved by
```

## Dry-Run Behavior

With `DRY_RUN=true`:

```text
Clone origin.
Compute version.
Create local branch.
Apply edits.
Generate diffs.
Optionally build locally.
Do not commit.
Do not push branches.
Do not create/push tags.
Do not publish artifacts.
```

With `DRY_RUN=false`:

```text
Allow commits after approval.
Allow branch pushes after approval.
Allow tags after approval.
Allow artifact publishing after approval.
```

## Origin Safety Guardrails

The pipeline must fail early if `ORIGIN_URL` appears to be the real DragonFly repository.

Reject by default:

```text
crater.dragonflybsd.org
git.dragonflybsd.org
mirror-master.dragonflybsd.org
/repository/git/dragonfly.git on known production hosts
```

Also verify after cloning:

```sh
git remote -v
```

Expected:

```text
origin only
```

Reject if there are any extra remotes.

## Gitea Authentication

The Gitea API token must be stored in Jenkins Credentials, not in the Jenkinsfile, not in this plan, and not in the opencode conversation.

Recommended Jenkins credential:

```text
Kind: Username with password
Username: Gitea user or release bot user
Password: Gitea API token / personal access token
Suggested ID: gitea-dragonfly-release-token
```

The pipeline should reference only the credential ID:

```groovy
withCredentials([usernamePassword(
    credentialsId: 'gitea-dragonfly-release-token',
    usernameVariable: 'GITEA_USER',
    passwordVariable: 'GITEA_TOKEN'
)]) {
    // Git clone/push operations use ORIGIN_URL and temporary credentials.
}
```

Do not embed credentials directly in `ORIGIN_URL`. Keep the parameter as:

```text
https://git.example.net/<you>/DragonFlyBSD.git
```

The implementation should use a temporary credential helper or `GIT_ASKPASS` so the token does not need to be entered repeatedly and is not printed in Jenkins logs.

Recommended GPG credentials:

```text
Kind: Secret file
ID: release-rehersal-gpg-private-key
Content: ASCII-armored GPG private key

Kind: Secret text
ID: release-rehersal-gpg-passphrase
Secret: passphrase for the GPG private key
```

## Signed Tags

Tags must always be signed. The pipeline must never fall back to unsigned tags.

Dry-run behavior:

```text
Do not create a tag.
Print the exact signed tag command that would be run.
Run a GPG preflight when possible.
```

Real run behavior:

```text
Require GPG signing to work.
Run git tag -s.
Fail the release immediately if signing fails.
Push the signed tag only after the single approval gate has passed.
```

GPG setup options:

```text
Create/import a Jenkins-owned signing key into Jenkins Credentials.
Store the ASCII-armored private key as a Jenkins Secret file credential.
Store the private key passphrase as a separate Jenkins Secret text credential.
Import that key into a temporary GNUPGHOME on the ephemeral worker during the build.
Configure git config user.signingkey to the imported key fingerprint.
Configure git config gpg.program to a temporary wrapper that invokes gpg with loopback pinentry and the temporary passphrase file.
Delete the temporary GNUPGHOME at the end of the build via workspace cleanup.
```

The key and passphrase should not be installed permanently on ephemeral worker nodes. They also do not need to live on the Jenkins controller filesystem. Jenkins Credentials should deliver them to the worker only during the signed-tag stage.

## Build Workflow

Artifact builds are out of scope for the first implementation. After Git operations succeed, the first Jenkinsfile stops after pushing the signed tag and archiving metadata/diffs.

Later, after the Git workflow is proven, build from the pushed tag/ref.

For RC:

```text
git checkout vX.Y.ZrcN
```

For final:

```text
git checkout vX.Y.Z
```

Then build release artifacts according to the DragonFly release process.

Artifacts should include:

```text
x86_64 iso
x86_64 img
x86_64 gui-img
compressed artifacts
uncompressed checksums
compressed checksums
build logs
metadata JSON
```

The first implementation may only build the currently supported Jenkins architecture, then expand.

## Checksum And Metadata Outputs

Generate and archive:

```text
md5.txt
sha256.txt
release-metadata.json
master.patch
release-branch.patch
build logs
```

Metadata should include:

```text
origin_url
base_branch
release_branch
release_tag
current_version
target_version
version_bump
release_stage
rc_number
jenkins_build_number
git commit ids
artifact names
checksum values
approval timestamps if available
```

## Publishing

For the first version, archive only in Jenkins.

Do not publish to:

```text
/ftp/iso-images
```

until the process is trusted and production use is explicitly planned.

## Manual Testing Gate

Keep a manual checklist stage for release validation:

```text
Boot install media.
Install encrypted.
Install unencrypted.
Install UFS.
Install HAMMER.
Configure DHCP.
Configure static IP.
Boot installed system.
Test on real hardware.
Test on qemu.
Test on vmware.
```

The pipeline should not claim the release is done until this gate is approved.

## Suggested Stage Layout

```text
Validate Parameters
Clone Origin
Compute Release Plan
Check Remote Refs
Prepare Release Changes
Archive Review Material
Approval Gate
Install Signing Tools
GPG Preflight
Commit Push And Tag
Dry Run Summary
Workspace Cleanup
```

The approval gate must happen before `Install Signing Tools` and `GPG Preflight` so the GPG private key and passphrase are not imported into the workspace while Jenkins is waiting for human approval.

## First Implementation Scope

Start with scope A:

```text
Clone configurable ORIGIN_URL.
Validate safe origin.
Compute and derive release values.
Create local release branch.
Apply source edits.
Generate diffs.
Require one approval gate with one approver.
Commit and push to cloned origin when DRY_RUN=false.
Create and push signed tag when DRY_RUN=false.
Archive release metadata.
```

Then add artifact building after the Git workflow is proven.

## Open Questions

1. Confirm Jenkins GPG signing key fingerprint and non-interactive signing setup.
2. Later enhancement: decide whether patch releases should support optional source-file edits.
3. Confirm the exact policy for `__DragonFly_version` increments in `sys/sys/param.h` for automated release branch and master edits.
