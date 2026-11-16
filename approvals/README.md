# Public release approvals

Each release uses `approvals/<release-tag>/release.json` and
`approvals/<release-tag>/second-signature.asc`. The manifest identifies the source
repository, release branch, tag name, exact signed tag object ID, and commit.
The detached signature approves the exact manifest bytes.

Create the pair with the trusted `scripts/approve-release` tool after independently
reviewing the source and first signature. Commit both files together. Do not edit
the manifest after signing, or overwrite published approval evidence.

Only public manifests and signatures belong here. No private keys or passphrases.
Jenkins reads these files as data from this repository's approval branch; it runs
verification code and loads authorized public keys from a separately pinned commit.
See `docs/signing.md` for the complete two-person process.

Each tag directory is bound to one source repository URL. Use distinct test
versions or RC numbers for rehearsals so their paths do not collide with official
release approvals. Evidence from one origin cannot approve a different origin.
