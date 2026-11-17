# Release signing

A release requires two independent authorized primary GPG keys: one signs the
Git tag; the other signs a public approval manifest binding the exact signed tag
object to the source repository and commit. Jenkins never receives private keys.
Assign the authorized keys to different maintainers; cryptography distinguishes
primary keys, not the humans who control them. Different subkeys of one primary
key do not count as two signers.

## Guided signing and publication

After installing the requirements below, each maintainer runs one command from
an up-to-date, reviewed CI/CD checkout. Use the expected source URL and your own
full primary fingerprint, obtained independently of the downloaded request:

```sh
python scripts/release-sign tag \
  --build-url https://ci.dragonflybsd.org/job/DragonFly%20BSD%20Engineering%20Release/BUILD_NUMBER/ \
  --source-dir ~/s/dragonfly-release \
  --origin EXPECTED_SOURCE_ORIGIN --key FIRST_PRIMARY_FINGERPRINT
```

The first maintainer reviews the displayed source changes, authorizes publication,
confirms the release review, and signs locally. The command pushes only that tag.
A missing source checkout is cloned automatically. Existing checkouts keep their
working tree and current branch; the request identifies the commit to review.

The second maintainer uses their own reviewed CI/CD checkout and independent key:

```sh
python scripts/release-sign approve \
  --build-url https://ci.dragonflybsd.org/job/DragonFly%20BSD%20Engineering%20Release/BUILD_NUMBER/ \
  --source-dir ~/s/dragonfly-release \
  --origin EXPECTED_SOURCE_ORIGIN --key SECOND_PRIMARY_FINGERPRINT
```

This verifies the first signature, presents source review, asks for publication consent,
creates the detached approval after review, and commits and pushes the two approval files. The
CI/CD checkout must be clean, on master, and match origin/master before publication;
other unpushed commits are refused. A cancelled or failed publication leaves the
signed evidence local for inspection and recovery using the manual steps below.
No force pushes are used. Git authentication uses the developer's usual setup.
Passphrases remain in GPG pinentry; GPG_TTY is set from the invoking terminal.

Use `--request /path/to/release-signing-request.json` instead of `--build-url`
for an already downloaded request. `--previous-tag` retains the manual comparison
override. Request downloads are limited to 64 KiB and require HTTPS. The expected
origin and review confirmations remain mandatory; a build URL is not a trust root.
The lower-level scripts below remain available for manual review and recovery.

Jenkins runs the pinned `scripts/poll-release-signing` for each signing phase.
It owns temporary askpass cleanup, fetches, and verifier invocation. Exit 2 means
missing evidence; network and verification failures stop the pipeline. Jenkins
owns only scheduling, timeouts, credentials binding, and evidence archiving.

## Trust configuration

Before a real run, an administrator configures `RELEASE_TOOLS_COMMIT` as the full
40-character hash of a reviewed commit in this CI/CD repository. That commit
must contain the current tools, schemas, and public keys for at least two
independent signers under `keys/authorized-signers/*.asc`. Verify fingerprints
independently before reviewing/committing the public keys. The directory is the
allowlist; the preflight counts distinct primary keys, not files or subkeys.
Revoked and expired primary keys do not satisfy that count.

Configure this as a job-level String Parameter named `RELEASE_TOOLS_COMMIT`, with
the reviewed hash as its default. Jenkins exports it to the build environment;
no global variable is required. An environment variable is also supported.
Anyone allowed to choose this parameter can select the executable verifier and
key revision, so restrict Build permission to trusted release operators and
review the selected hash before approval. This pins the build's tools; it is not
an independently protected trust policy. Protect job configuration, Pipeline
source and Replay permissions as well.

Jenkins fetches exactly this tools commit and checks out a detached HEAD in
`tools/`; there is no fallback to a moving branch. It fetches approvals separately
from `RELEASE_APPROVALS_REF` (currently `master`) into a bare `approval-data.git`
repository using the same CI/CD URL. It extracts only the two approval blobs;
it never executes scripts or loads keys from the approval branch. New approval
commits therefore cannot change the pinned verification rules. Updating trusted
tools or authorized keys requires reviewing a new commit and deliberately
updating `RELEASE_TOOLS_COMMIT`.

Dry runs do not require a pin, signers or signatures, and publish nothing.

When deploying the extracted signing-poll stages, select a reviewed tools commit
containing `scripts/poll-release-signing`. Older pins are rejected before branch
publication, rather than failing after the release has already been prepared.

## Requirements

Tag/approval tools require Git, GPG, Python 3.10+, and `jsonschema` (4.23+, below 5).
Keep the tools together with their `release_*.py` modules and `schemas/` directory.
A signer can use a virtual environment:

```sh
python3 -m venv /path/to/signing-venv
. /path/to/signing-venv/bin/activate
python -m pip install -r scripts/requirements-signing.txt
```

Jenkins explicitly uses Python 3.12. The early *Checkout Release Tools* stage
installs missing DragonFly binary packages `python312`, `py312-jsonschema` and
`gnupg`, validates the schema and checks for two authorized primary keys before
any branch push. Workers need package installation permission when these are
absent. Binary packages avoid building Python dependencies from source.

## First signer: review and sign the Git tag

Use tools from an independently reviewed checkout of the pinned CI/CD commit.
The source URL passed as `--origin` must be independently known, rather than
blindly copied from an untrusted request. Run inside your DragonFly source checkout:

```sh
/path/to/trusted-cicd/scripts/sign-release-tag \
  --key FIRST_PRIMARY_FINGERPRINT \
  --origin https://your-gitea/your/DragonFlyBSD.git \
  /path/to/release-signing-request.json
```

The script requires the full primary fingerprint and an authorized local secret
key. It validates the JSON schema and consistency of version, branch, stage,
RC number and label. It checks that the request's origin and local `origin`
match the expected URL, fetches the release branch, and requires its tip to be
the exact requested commit. It rejects tags already present locally or remotely.
It displays the release identity, up to 50 commits and a diff summary since the
highest earlier even-minor final release version on origin; odd-minor development
tags such as `v6.5.0` are excluded. `--previous-tag vX.Y.Z` selects
another final comparison tag. A comparison release need not be an ancestor:
maintenance branches may contain cherry-picks absent from master.

Review the full source diff independently; a summary cannot establish source
correctness. Terminal control characters in commit/filename output are escaped.
If no earlier final tag exists, the script explicitly identifies this as an
initial revision requiring independent review. It requires typing the exact
release tag before signing, rechecks the remote branch/tag afterward, and signs
with the selected local GPG key. Push the resulting tag yourself:

```sh
git push origin refs/tags/v6.6.0rc1:refs/tags/v6.6.0rc1
```

Jenkins emits `release-signing-request.json` and `tag-command.txt` as artifacts.
The request records the tools commit for auditing. Jenkins first waits up to
`SIGNED_TAG_TIMEOUT_HOURS` (default 3h) for the signed tag, verifies its first
signature, and then waits for the second approval. A present invalid tag fails
immediately; it is never accepted as a reason to continue.

## Second signer: approve the exact signed release

A different maintainer independently reviews the source and runs, inside their
source checkout:

```sh
/path/to/trusted-cicd/scripts/approve-release \
  --key SECOND_PRIMARY_FINGERPRINT \
  --origin https://your-gitea/your/DragonFlyBSD.git \
  --output-dir /path/to/cicd-worktree/approvals \
  /path/to/release-signing-request.json
```

This repeats the repository/commit review, fetches the remote tag without
replacing any local tag, and verifies the first signature using the trusted
public-key allowlist. It refuses a second key with the same primary fingerprint.
After explicit confirmation and a final remote-ref check, it creates:

```text
approvals/v6.6.0rc1/release.json
approvals/v6.6.0rc1/second-signature.asc
```

The manifest contains a fixed `dragonfly-release-approval` context, repository
URL, branch, tag, signed tag object ID and commit. The detached signature covers
its exact bytes. It cannot approve a different repository, commit, tag object or
release name. Existing approval directories are not overwritten. Review and commit
both files in the CI/CD worktree, then publish them using the normal repository
review process. No secret material belongs in this repository. Each tag directory identifies
one source origin; use distinct test versions/RC numbers for rehearsals to avoid
collisions with official release approval paths.

Jenkins polls for the pair for up to `SECOND_APPROVAL_TIMEOUT_HOURS` (default 3h).
A missing/partial pair waits; fetch errors or invalid evidence fail the build.
Files must be regular, non-executable Git blobs, each at most 64 KiB. Symlinks are
never followed. One approval commit is resolved and recorded for the pair.

Before success, Jenkins fetches the current source tag again so a replacement
during the approval wait cannot be hidden by a stale local ref. The pinned
verifier rechecks both signatures against the pinned
keys, requires distinct primary fingerprints, and compares the manifest with
the requested source and exact signed tag object. Jenkins archives the manifest,
detached signature, approval source commit and a JSON verification report with
both primary fingerprints. `git verify-tag` alone verifies only the first
signature; the full policy is checked by:

```sh
scripts/verify-release-approval release-signing-request.json /path/to/approval-pair
```

## Validation and security boundaries

Both JSON schemas reject unknown/missing fields and invalid types. Parsing also
rejects duplicate fields, control characters, nonstandard JSON constants, and
inputs over 64 KiB. Request fields must agree on an even release minor and the
canonical label. Final requests cannot contain `rc_number`.

Tag verification requires a valid authorized OpenPGP signature, the signed
internal tag name, and a direct reference to the exact requested commit. It
resolves the tag once and uses that immutable object ID throughout. Nested or
unsigned tags and signed tags copied under another name are rejected. It uses
an isolated public-key keyring, disables Git replacement objects, and ignores
repository overrides of the GPG verification program. Expired/revoked signature
or key status fails verification. Subkeys map to their primary fingerprint.

The trust boundary is the reviewed pipeline/tools revision, public-key
registration, independent human review and private-key custody. A signature
proves what a key signed, not that the source is correct. The source branches
are already published before external signing; failed signing does not roll back
those pushes. Re-run on the release branch with `VERSION_BUMP=none` and the same
explicit target/stage/RC to resume without branch edits. Existing evidence is
accepted only if it still matches the source/tag and current pinned key policy.

Run the real Git/GPG checks with disposable keys (personal keyrings are untouched):

```sh
python -m unittest discover -s tests -p 'test_release_*.py' -v
groovy tests/release-version.groovy
RELEASE_SIGNING_TEST_PYTHON=/absolute/path/to/signing-venv/bin/python \
  groovy tests/release-workflow.groovy
```

The Groovy integration checks execute the actual Git/signing stages, including
pinned-tool checkout, the complete two-person flow and a moving approval commit
that maliciously changes scripts/keys. Jenkins CPS and UI approval integration
still require a real rehearsal.

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
- The poll timeout (`SIGNED_TAG_TIMEOUT_HOURS`, default 3h) and poll interval are
  tunable.
- Artifact/image signing is wired as skeletons; it activates once the build
  workflow exists.
