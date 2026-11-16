"""OpenPGP verification using only the reviewed public-key allowlist."""

from contextlib import contextmanager
import os
from pathlib import Path
import subprocess
import tempfile

from release_signing import SigningError, command, git, require_commit

def primary_fingerprints(listing):
    fingerprints = set()
    primary = False
    for line in listing.decode("utf-8", errors="replace").splitlines():
        fields = line.split(":")
        if fields[0] in ("pub", "sub", "sec", "ssb"):
            primary = fields[0] in ("pub", "sec") and fields[1] not in ("r", "e", "d")
        elif fields[0] == "fpr" and primary:
            fingerprints.add(fields[9])
            primary = False
    return fingerprints


@contextmanager
def authorized_keyring(keys_dir=None, minimum=1):
    keys_dir = Path(keys_dir or os.environ.get(
        "AUTHORIZED_SIGNERS_DIR", Path(__file__).resolve().parent.parent / "keys/authorized-signers"
    ))
    keys = sorted(keys_dir.glob("*.asc"))
    if not keys:
        raise SigningError(f"no authorized signer public keys in {keys_dir}")

    with tempfile.TemporaryDirectory(prefix="release-verify-") as home:
        os.chmod(home, 0o700)
        env = dict(os.environ, GNUPGHOME=home)
        for key in keys:
            command(["gpg", "--homedir", home, "--no-options", "--no-autostart", "--batch", "--quiet",
                     "--import", str(key.resolve())], env=env)
        listing = command(["gpg", "--homedir", home, "--no-options", "--no-autostart", "--batch", "--with-colons",
                           "--list-keys"], env=env)
        allowed = primary_fingerprints(listing)
        if not allowed:
            raise SigningError("authorized public keyring is empty")
        if len(allowed) < minimum:
            raise SigningError(f"release policy requires at least {minimum} distinct authorized primary keys")
        yield env, allowed


def signature_signer(result, allowed):
    if result.returncode:
        raise SigningError("signature did not verify against authorized public keys")
    status = result.stderr.decode("utf-8", errors="replace")
    valid = []
    for line in status.splitlines():
        fields = line.split()
        if fields[:2] == ["[GNUPG:]", "VALIDSIG"]:
            # Last (optional) field is the primary fingerprint for a subkey.
            valid.append(fields[11] if len(fields) == 12 else fields[2])
        if len(fields) > 1 and fields[0] == "[GNUPG:]" and fields[1] in {
            "BADSIG", "ERRSIG", "EXPSIG", "EXPKEYSIG", "REVKEYSIG", "KEYEXPIRED", "SIGEXPIRED"
        }:
            raise SigningError("tag has an invalid, expired or revoked signature/key")
    if len(valid) != 1 or valid[0] not in allowed:
        raise SigningError("tag must have one valid signature from an authorized primary key")
    return valid[0]


def inspect_tag(request, oid=None):
    tag = request["release_tag"]
    commit = request["commit"]
    git("rev-parse", "--git-dir")
    require_commit(commit)
    # Resolve once and use this immutable object ID for every subsequent check.
    if oid is None:
        oid = git("rev-parse", "--verify", f"refs/tags/{tag}").decode("ascii").strip()
    if git("cat-file", "-t", oid).strip() != b"tag":
        raise SigningError("release tag must be an annotated, signed tag")
    headers, separator, _ = git("cat-file", "tag", oid).partition(b"\n\n")
    if not separator:
        raise SigningError("malformed tag object")
    values = {}
    for line in headers.split(b"\n"):
        name, space, value = line.partition(b" ")
        if not space or name in values:
            raise SigningError("malformed or duplicate tag header")
        values[name] = value
    if values.get(b"tag") != tag.encode("ascii"):
        raise SigningError("signed internal tag name does not match requested release_tag")
    if values.get(b"type") != b"commit" or values.get(b"object") != commit.encode("ascii"):
        raise SigningError("signed tag must point directly at the exact requested commit")

    return oid


def verify_tag(request, keys_dir=None, keyring=None, oid=None):
    oid = inspect_tag(request, oid)
    def check(env, allowed):
        # Ignore repository overrides of the signature format and executable.
        result = subprocess.run(
            ["git", "-c", "gpg.format=openpgp", "-c", "gpg.program=gpg",
             "-c", "gpg.openpgp.program=gpg", "verify-tag", "--raw", oid],
            capture_output=True, env=dict(env, GIT_NO_REPLACE_OBJECTS="1")
        )
        return oid, signature_signer(result, allowed)
    if keyring is not None:
        return check(*keyring)
    with authorized_keyring(keys_dir) as (env, allowed):
        return check(env, allowed)


def verify_detached(payload, signature, keyring):
    env, allowed = keyring
    with tempfile.TemporaryDirectory(prefix="release-approval-") as directory:
        data = Path(directory) / "release.json"
        sig = Path(directory) / "second-signature.asc"
        data.write_bytes(payload)
        sig.write_bytes(signature)
        result = subprocess.run(
            ["gpg", "--no-options", "--batch", "--status-fd", "2", "--verify", str(sig), str(data)],
            capture_output=True, env=env
        )
        return signature_signer(result, allowed)
