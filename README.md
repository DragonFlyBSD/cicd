# cicd

CI/CD and release-automation tooling for DragonFly BSD.

This repository holds the Jenkins pipelines, scripts, schemas, and design docs
used to automate the DragonFly release process. It is intentionally **separate
from the DragonFly source repository** — nothing here is part of the OS source
tree. The release Git workflow currently operates against a writable Gitea clone
of the DragonFly repo for testing, not the canonical repository.

## Layout

```
jenkins/
  Jenkinsfile.release-git              Release Git-workflow pipeline (derived
                                       from the release-rehearsal pipeline).
  active-choices/                      Reference copies of the Active Choices
                                       parameter scripts (BASE_BRANCH).
docs/
  release-pipeline.md                  Design/plan for the release pipeline.
  signing.md                           Release signing model and known keys.
scripts/
  sign-release-tag                     Create a signed release tag from a request.
  verify-release-tag                   Verify a signed tag against its request.
  sign-release-artifacts               Build + sign a sha256 CHECKSUM manifest.
  verify-release-artifacts             Verify the manifest signature and hashes.
schemas/
  release-signing-request.schema.json  Schema for the signing request document.
keys/
  authorized-signers/                  Public keys of authorized release signers
                                       (the verification allowlist).
```

## Start here

- `docs/release-pipeline.md` — what the pipeline does, parameters, stages, and
  guardrails.
- `docs/signing.md` — how release tags are signed today and the direction toward
  signing outside Jenkins.
