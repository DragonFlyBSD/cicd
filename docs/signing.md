# Release Signing

This document describes how DragonFly release tags (and, later, release
artifacts) are signed.

## Model: sign outside Jenkins

`jenkins/Jenkinsfile.release-git` does **not** sign in Jenkins and holds no
signing private key. It commits and pushes the release branches, emits a signing
request, and then waits for an authorized signer to push the signed tag, which
it verifies before the build completes. Scope is release **tags** now, and
release **artifacts/images** (`.iso` / `.img` plus checksums) later, once a build
workflow exists — same out-of-Jenkins pattern (see "Signing release artifacts").

Tags are **always** signed and verified against an allowlist; the pipeline never
accepts an unsigned or unauthorized tag. In dry-run mode it pushes nothing and
emits no request.

> Historical note: an earlier rehearsal pipeline signed tags inside Jenkins
> using `release-rehersal-gpg-private-key` / `release-rehersal-gpg-passphrase`
> credentials. That approach is retired here; those credentials are no longer
> used by `Jenkinsfile.release-git`.

### Flow

1. Jenkins emits, as build artifacts:
   - `release-signing-request.json` — the machine interface, validated against
     `schemas/release-signing-request.schema.json`.
   - `tag-command.txt` — a transparent, human-readable fallback containing the
     exact `git tag -s ...` invocation. Useful when the script is unavailable
     and as an auditable record of what is being signed.
2. An authorized signer, on their own machine, runs:
   ```
   scripts/sign-release-tag release-signing-request.json
   ```
   This creates the signed tag with the signer's own key — no Jenkins key
   involved.
3. The signer pushes the signed tag back to origin. Jenkins detects it and
   verifies before doing anything else with it:
   ```
   scripts/verify-release-tag release-signing-request.json
   ```
   Verification fails closed: invalid signatures, unauthorized signers, mismatched
   internal tag names, and tags not pointing directly at the exact requested
   commit are rejected.

The script interface (`sign-release-tag` / `verify-release-tag`) is the primary
path; `tag-command.txt` is the transparent fallback.

### Requirements and request validation

The tag scripts require Git, GPG, Python 3.10 or newer, and `jsonschema` 4.23 or
newer (below 5). Keep the scripts together with `scripts/release_signing.py` and
`schemas/release-signing-request.schema.json`; the helper loads the local schema.
For a signer or local tests, prepare a Python environment and activate it:

```sh
python3 -m venv /path/to/signing-venv
. /path/to/signing-venv/bin/activate
python -m pip install -r scripts/requirements-signing.txt
python -m unittest discover -s tests -p test_release_signing.py -v
```

The integration tests generate disposable OpenPGP keys in temporary directories;
they do not read or modify your personal keyring. They cover authorized signing
subkeys, final/RC releases, malformed/duplicate JSON, inconsistent fields,
unauthorized signatures, incorrect commits, unsigned and nested tags, reused tag
objects, tampered signatures, and repository verification-program overrides.

Both tag scripts parse the request as JSON, reject duplicate fields and control
characters, and enforce the committed Draft 2020-12 JSON schema. Requests are
limited to 64 KiB of text. Additional checks require an even release minor and
agreement between the target version, release branch, tag name, stage, RC number,
and canonical tag message (`DragonFly x.y.z` or `DragonFly x.y.zrcN`). Final
requests cannot contain `rc_number`. Validation happens before signing or tag
verification. To check a request without accessing Git or signing keys:

```sh
scripts/validate-release-signing-request release-signing-request.json
```

Jenkins serializes requests using a JSON encoder and validates them before
archiving. Its early *Checkout Release Tools* stage checks Python/GPG availability
and explicitly uses Python 3.12, installing missing DragonFly binary packages
(`python312`, `gnupg`, and `py312-jsonschema`). It invokes `python3.12` regardless
of the system default. This avoids building
Python dependencies from source on the worker and runs before branch pushes.
The worker needs package installation permission when dependencies are absent.

The signer's independent repository/source review and explicit key selection are
still manual; request validation does not establish that the requested source is
safe to release.

### Authorized signers (the allowlist)

`verify-release-tag` does not trust any key that happens to be in a keyring. It
imports only the public keys committed under `keys/authorized-signers/*.asc`
into a throwaway keyring, verifies the tag against that keyring, and then
requires the signing key's primary fingerprint to be one of those keys. The
directory **is** the allowlist; override its location with
`AUTHORIZED_SIGNERS_DIR`.

Only primary public-key fingerprints are allowlisted; signatures by their
signing subkeys are mapped back to the primary fingerprint. Verification uses
an isolated temporary GPG home and explicitly selects GPG/OpenPGP so repository
configuration cannot substitute another verification program. Invalid, expired,
or revoked signature/key status fails verification.

The verifier resolves the requested tag ref once and checks that immutable tag
object throughout. It requires an annotated tag, the signed object's internal
`tag` header to match `release_tag`, and its direct object/type headers to name
the requested commit. A signed RC tag copied under a final tag ref is therefore
rejected, even if it targets the same commit. Nested tags are rejected. Git
replacement objects are disabled for these checks.

This fails closed: if the directory has no keys, verification rejects
everything. See `keys/authorized-signers/README.md`. The final production
key(s) are still to be decided (below), so no keys are committed yet.

### Pausing the pipeline until the tag is signed (automated poll)

The pipeline must not continue until the signed tag is on origin and validates.
It does this by polling origin for the tag and verifying it when it appears,
bounded by a timeout. Sketch of the stage (declarative pipeline):

```groovy
stage('Publish Signing Request') {
    steps {
        // ... after committing + pushing branches and the release commit ...
        writeFile file: 'release-signing-request.json', text: requestJson
        writeFile file: 'tag-command.txt', text: tagCommand
        archiveArtifacts artifacts: 'release-signing-request.json,tag-command.txt'
    }
}

stage('Await Signed Tag') {
    when { expression { return !params.DRY_RUN } }
    steps {
        timeout(time: 3, unit: 'HOURS') {   // SIGNED_TAG_TIMEOUT_HOURS
            waitUntil(initialRecurrencePeriod: 60000) {   // poll ~every 60s
                script {
                    // Tag present on origin yet?
                    def present = sh(returnStatus: true, script: '''
                        set -eu
                        git ls-remote --exit-code --tags "$ORIGIN_URL" \
                            "refs/tags/$RELEASE_TAG" >/dev/null 2>&1
                    ''') == 0
                    if (!present) { return false }
                    // Fetch it and verify; only a passing verify ends the wait.
                    return sh(returnStatus: true, script: '''
                        set -eu
                        git -C src fetch --tags origin "refs/tags/$RELEASE_TAG"
                        scripts/verify-release-tag release-signing-request.json
                    ''') == 0
                }
            }
        }
    }
}
```

Notes:
- This is implemented in `jenkins/Jenkinsfile.release-git` (stages *Commit And
  Push Branches* → *Emit Signing Request* → *Await Signed Tag*). Because that job
  keeps release tooling separate from its source checkout, it clones **this**
  repo to `tools/` on the agent and runs `tools/scripts/verify-release-tag` with
  `AUTHORIZED_SIGNERS_DIR=tools/keys/authorized-signers`.
- The agent only needs the authorized **public** keys (from `keys/`), never a
  private key — so the whole wait happens with no signing material on the worker.
- A present tag that does not verify (bad signature, unauthorized signer, mismatched internal name, or
  wrong commit) is treated as a **hard failure**, not a reason to keep waiting.
  Only a not-yet-present tag keeps the loop polling; the surrounding `timeout`
  bounds that wait.
- Until an authorized public key is committed under `keys/authorized-signers`,
  verification fails closed. To avoid pushing branches only to then block on a
  tag that can never verify, the pipeline checks the allowlist is non-empty in
  the early *Checkout Release Tools* stage (right after cloning origin, before
  approval and any push) and fails fast if it is empty.
- The wait is bounded by `SIGNED_TAG_TIMEOUT_HOURS` (default 3h).

## Signing release artifacts

Release images (`*.iso.bz2`, `*.img.bz2`, gui-img, …) are covered by **one
signed sha256 manifest** rather than a signature per file:

1. The build computes a `CHECKSUM` manifest (sha256 of every artifact, GNU
   coreutils `"<hex>  <path>"` format).
2. An authorized signer signs that one file off the Jenkins host:
   ```
   scripts/sign-release-artifacts -C <release-dir> dfly-x86_64-6.6.0.iso.bz2 ... 
   ```
   producing a detached `CHECKSUM.asc`. The single signature transitively covers
   every artifact through its hash.
3. Jenkins verifies before publishing:
   ```
   scripts/verify-release-artifacts -C <release-dir>
   ```
   which checks the `CHECKSUM.asc` signature against the **same**
   `keys/authorized-signers` allowlist used for tags, then recomputes every hash
   in the manifest. It fails closed on a bad/unauthorized signature, a missing
   artifact, or any hash mismatch.

Publishing the `CHECKSUM` + `CHECKSUM.asc` alongside the images lets downstream
users verify with stock `gpg --verify CHECKSUM.asc CHECKSUM` and `sha256sum -c`.
Artifact builds are out of scope for the first implementation, so these scripts
are skeletons today.

## Known keys (reference only)

These are observed signing keys, recorded for reference. They are candidates for
the `keys/authorized-signers` allowlist but are **not** committed there yet — see
the open question below.

| Fingerprint                                | Identity                                                     |
| ------------------------------------------ | ----------------------------------------------------------- |
| `98B32BEEDC396D3EDF791F471D9C521BE59E524C` | Antonio Huete Jimenez (Commits key)                         |
| `0E3A8560AC19C01D44F735872CEF94C649944635` | Security Officer (DragonFly BSD) <security@dragonflybsd.org> |
| `18167E016CEC3A87E28EAFCC55176CEF8B01DD89` | justin@shiningsilence.com (older releases, e.g. 6.0.1, 6.2.0) |

## Open questions

- Final authorized production signing key(s) are **not yet decided**. The
  allowlist mechanism is in place (`keys/authorized-signers/*.asc`), but no keys
  are committed, so verification fails closed until they are chosen and added.
- The signer pushes the signed tag to origin; the pipeline polls for it.
- Verification tooling and keys still come from the configured tools branch.
  Pinning that trust configuration to a reviewed revision is a separate follow-up.
- The poll timeout (`SIGNED_TAG_TIMEOUT_HOURS`, default 3h) and poll interval are
  tunable.
- Artifact/image signing is wired as skeletons; it activates once the build
  workflow exists.
