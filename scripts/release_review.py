"""Repository checks and human review shared by both release signers."""

import re
import subprocess
import unicodedata

from release_signing import SigningError, command, git, require_commit
from release_verification import primary_fingerprints


TRANSPORT = ('-c', 'protocol.allow=never', '-c', 'protocol.https.allow=always',
             '-c', 'protocol.ssh.allow=always', '-c', 'protocol.git.allow=always',
             '-c', 'protocol.file.allow=always')


def display(text):
    # Commit messages/filenames must not inject terminal control sequences.
    print(''.join(character if character in '\n\t' or not unicodedata.category(character).startswith('C')
                  else ascii(character)[1:-1] for character in text))


def signing_key(fingerprint):
    if not re.fullmatch(r'(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})', fingerprint):
        raise SigningError('--key must be a full primary GPG fingerprint')
    fingerprint = fingerprint.upper()
    listing = command(['gpg', '--no-options', '--batch', '--with-colons', '--list-secret-keys', fingerprint])
    if fingerprint not in primary_fingerprints(listing):
        raise SigningError('--key must identify a locally available primary secret key, not a subkey')
    return fingerprint


def remote_refs(request, expected_origin):
    if request.get('origin_url') != expected_origin:
        raise SigningError('request origin_url does not match the explicitly expected --origin')
    local_origin = git('remote', 'get-url', 'origin').decode().strip()
    if local_origin != expected_origin:
        raise SigningError('local origin does not match the explicitly expected --origin')
    listing = git(*TRANSPORT, 'ls-remote', '--heads', '--tags', '--refs', 'origin').decode('utf-8')
    refs = dict((line.split()[1], line.split()[0]) for line in listing.splitlines())
    if refs.get(f"refs/heads/{request['release_branch']}") != request['commit']:
        raise SigningError('requested commit does not match the remote release branch tip')
    return refs


def review_source(request, expected_origin, previous_tag=None):
    refs = remote_refs(request, expected_origin)
    git(*TRANSPORT, 'fetch', '--no-tags', 'origin', f"refs/heads/{request['release_branch']}")
    require_commit(request['commit'])
    if git('rev-parse', 'FETCH_HEAD').decode().strip() != request['commit']:
        raise SigningError('release branch changed while fetching; restart the review')
    target = tuple(int(part) for part in request['target_version'].split('.'))
    if previous_tag is None:
        candidates = []
        for ref in refs:
            match = re.fullmatch(r'refs/tags/v(\d+)\.(\d+)\.(\d+)', ref)
            if match:
                version = tuple(map(int, match.groups()))
                # DragonFly also tags odd-minor DEVELOPMENT versions (v6.5.0).
                # They are not the previous final release for source review.
                if version[1] % 2 == 0 and version < target:
                    candidates.append((version, ref.removeprefix('refs/tags/')))
        previous_tag = max(candidates)[1] if candidates else None
    previous_commit = None
    if previous_tag:
        match = re.fullmatch(r'v(\d+)\.(\d+)\.(\d+)', previous_tag)
        version = tuple(map(int, match.groups())) if match else None
        if (version is None or version[1] % 2 or version >= target or
                f'refs/tags/{previous_tag}' not in refs):
            raise SigningError('--previous-tag must name an existing earlier final release tag on origin')
        git(*TRANSPORT, 'fetch', '--no-tags', 'origin', f'refs/tags/{previous_tag}')
        if git('rev-parse', 'FETCH_HEAD').decode().strip() != refs[f'refs/tags/{previous_tag}']:
            raise SigningError('previous release tag changed while fetching; restart the review')
        previous_commit = git('rev-parse', 'FETCH_HEAD^{commit}').decode().strip()

    display(f"Repository: {expected_origin}\nBranch: {request['release_branch']}\n"
            f"Release tag: {request['release_tag']}\nCommit: {request['commit']}")
    if previous_commit:
        display(f'Comparison release: {previous_tag} ({previous_commit})')
        display(git('--no-pager', 'log', '--no-decorate', '--no-show-signature', '--format=short',
                    '--max-count=50', f"{previous_commit}..{request['commit']}").decode(errors='replace'))
        display(git('--no-pager', 'diff', '--no-ext-diff', '--no-textconv', '--stat',
                    previous_commit, request['commit'], '--').decode(errors='replace'))
        display(f"Inspect the full diff before confirming: git --no-pager diff --no-ext-diff "
                f"--no-textconv {previous_commit} {request['commit']} --")
    else:
        display('No earlier final release tag found. Review this initial source revision independently.')
        display(git('--no-pager', 'show', '--no-ext-diff', '--no-textconv', '--stat',
                    request['commit'], '--').decode(errors='replace'))
    return refs


def confirm(request, action):
    try:
        answer = input(f"Type {request['release_tag']} to {action} this exact release: ")
    except EOFError as exc:
        raise SigningError('confirmation required; no signing was performed') from exc
    if answer != request['release_tag']:
        raise SigningError('confirmation did not match; no signing was performed')
