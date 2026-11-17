# Release rehearsal 10: process and public-output review

Reviewed 2026-11-17. Build: <https://ci.dragonflybsd.org/job/DragonFly%20BSD%20Engineering%20Release/10/>.

## Scope and result

Reviewed the full publicly accessible console, all 13 archived artifacts, the
published approval commit, public-key packet types, and the credential/signing
paths in the pipeline and scripts. The build finished SUCCESS and confirmed
workspace cleanup. No private keys, passphrases, recognizable token formats,
credential-bearing URLs, or unexplained 40-hex values were found in that output.
This is a review of published evidence and code paths, not a forensic inspection
of Jenkins credential storage, worker disks, personal keyrings, or server ACLs.

## Evidence consistency

- Release: `v6.4.3rc1`, source commit
  `1c730f94627deb64772c0d5b0ddca2a65441346b`.
- Signed tag object: `02f57708388a4354d567f4934bca87041c31eed3`.
- Pinned tools/keys: `0f7bf996f497b03232f4df38ebd4533cde999f50`.
- Approval data: `e70ed8cd7917316177ff6591a65e88f82c1024f2`.
- The request, plan, metadata, approval manifest and verification report agree
  on release identity. Both signatures were independently reverified using the
  exact pinned tools and public keys against the local source tag object.
- Archived manifest/signature bytes match the recorded approval-data commit.
  That commit changed only the two intended public approval files.
- Both exported keys contain public-key packets, not secret-key packets. Their
  different primary fingerprints match the verification report.
- The branch patches and changed-file lists are empty, consistent with a
  patch/tag-only operation that leaves development untouched.
- No CPS mismatch warning appears in build 10; the computed next patch is 3.

## Information that is public

The console and artifacts are downloadable without authentication. They disclose
the private Gitea repository hostname/path, branch/tag and commit IDs, public key
fingerprints, Jenkins approver identity, build timestamps, and worker/workspace
paths. Exported public keys also contain their owners' public user IDs/emails.
These are metadata, not credentials, but must not be treated as confidential.

The source URL is also in the public Git approval manifest; restricting Jenkins
read access alone would not hide it. Do not edit the published signed manifest
to redact it: doing so invalidates the detached approval signature. Changing the
publication policy requires a deliberate format/workflow change.

No source diff content was exposed by this tag-only build. New major/minor runs
archive version-edit diffs with source-file context. If the source fork contains
confidential content, restrict artifact access before publishing those diffs.

## Credential handling

The Gitea credential is bound only around remote Git operations. The askpass
script contains environment-variable references rather than a literal token;
its output goes to Git rather than the console. Cleanup removes that script
and the workspace. Signing runs on the signer's machine; Jenkins imports only
public keys into temporary verification keyrings. No private-key export,
passphrase argument, or private keyring is archived or committed by this flow.

The two rehearsal keys are controlled by one person. This proves the distinct-key
enforcement, not operational separation between two production maintainers.
The editable tools-pin parameter also assumes trusted release operators; it is
not an independent policy boundary against someone who can select a malicious
tools commit or replace the pipeline.

## Small artifact defects fixed for future builds

1. `release-metadata.json` rendered the marker as `RELEASE_6.4` rather than the
   source marker `RELEASE_6_4`. Use an explicit canonical marker.
2. Groovy/shell line continuation collapsed the second-signing command in
   `tag-command.txt` into a commented-out argument. Emit the complete example on
   one line. Integration checks cover both corrected outputs.

Published build-10 evidence and signatures are left unchanged. This review found
no evidence requiring credential rotation. A live major/minor branch-publication
rehearsal and administrative access-control review remain separate checks.
