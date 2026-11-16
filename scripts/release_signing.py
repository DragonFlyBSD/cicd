"""Shared request validation and Git invocation for release tag scripts."""

import json
import os
from pathlib import Path
import re
import subprocess

try:
    from jsonschema import Draft202012Validator, SchemaError
except ImportError as exc:
    raise SystemExit(
        "Release signing requires jsonschema; install scripts/requirements-signing.txt "
        "in your Python environment."
    ) from exc


class SigningError(Exception):
    """A request or release tag failed validation."""


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise SigningError(f"duplicate JSON field: {key!r}")
        result[key] = value
    return result


def reject_constant(value):
    raise SigningError(f"invalid JSON constant: {value}")


def load_request(path):
    try:
        with open(path, "rb") as stream:
            content = stream.read(65537)
        if len(content) > 65536:
            raise SigningError("signing request exceeds 64 KiB")
        request = json.loads(
            content.decode("utf-8"), object_pairs_hook=unique_object, parse_constant=reject_constant
        )
        schema_path = Path(__file__).resolve().parent.parent / "schemas/release-signing-request.schema.json"
        with schema_path.open(encoding="utf-8") as stream:
            schema = json.load(stream)
        Draft202012Validator.check_schema(schema)
        errors = list(Draft202012Validator(schema).iter_errors(request))
        if errors:
            raise SigningError(f"request does not match schema: {errors[0].message}")
    except SchemaError as exc:
        raise SigningError(f"invalid signing request schema: {exc.message}") from exc
    except (OSError, UnicodeError, ValueError) as exc:
        raise SigningError(f"cannot read signing request: {exc}") from exc

    # No control characters may reach command arguments, logs or tag headers.
    for key, value in request.items():
        if isinstance(value, str) and re.search(r"[\x00-\x1f\x7f]", value):
            raise SigningError(f"control character in request field: {key}")

    major, minor, patch = (int(part) for part in request["target_version"].split("."))
    version = f"{major}.{minor}.{patch}"
    if minor % 2:
        raise SigningError("release minor version must be even")
    if request["release_branch"] != f"DragonFly_RELEASE_{major}_{minor}":
        raise SigningError("release_branch does not match target_version")
    suffix = ""
    if request["release_stage"] == "rc":
        suffix = f"rc{request['rc_number']}"
    elif "rc_number" in request:
        raise SigningError("final release request must not contain rc_number")
    if request["release_tag"] != f"v{version}{suffix}":
        raise SigningError("release_tag does not match target_version, release_stage and rc_number")
    if request["tag_message"] != f"DragonFly {version}{suffix}":
        raise SigningError("tag_message does not match the release version and stage")
    return request


def command(arguments, *, env=None, input=None):
    result = subprocess.run(arguments, input=input, capture_output=True, env=env)
    if result.returncode:
        details = result.stderr.decode("utf-8", errors="replace").strip()
        raise SigningError(f"{arguments[0]} failed: {details}")
    return result.stdout


def git(*arguments, env=None):
    # Replacement objects must not change the identity/content being checked.
    git_env = dict(os.environ if env is None else env, GIT_NO_REPLACE_OBJECTS="1")
    return command(["git", *arguments], env=git_env)


def require_commit(commit):
    if git("cat-file", "-t", commit).strip() != b"commit":
        raise SigningError("requested object is not a commit")
