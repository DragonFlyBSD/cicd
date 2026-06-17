# Authorized signer public keys

ASCII-armored **public** keys (`*.asc`) of the signers authorized to sign
DragonFly release tags and artifacts. Public keys are not secret; they are
committed here on purpose so the allowlist is transparent, auditable, and
version-controlled.

`scripts/verify-release-tag` and `scripts/verify-release-artifacts` import every
`*.asc` in this directory into a throwaway keyring and require the signature to
come from one of these keys. The directory is the allowlist.

**Fail-closed:** if this directory contains no `*.asc` files, verification
fails. Until the final authorized production key(s) are decided, no keys are
committed and verification will reject everything. Do not add a key here as a
placeholder just to make verification pass.

Candidate keys under discussion (see `docs/signing.md`):

- `98B32BEEDC396D3EDF791F471D9C521BE59E524C` — Antonio Huete Jimenez (Commits key)
- `0E3A8560AC19C01D44F735872CEF94C649944635` — Security Officer (DragonFly BSD)
- `18167E016CEC3A87E28EAFCC55176CEF8B01DD89` — justin@shiningsilence.com

To add a key once authorized:

```
gpg --armor --export <fingerprint> > keys/authorized-signers/<name>.asc
```

Only commit the exported **public** key. Never commit a private key.
