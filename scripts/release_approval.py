"""Second approval binds a source repository and release to one signed tag object."""

from pathlib import Path

from release_signing import SigningError, load_document
from release_verification import authorized_keyring, verify_detached, verify_tag


def approval_manifest(request, oid):
    if not request.get('origin_url'):
        raise SigningError('origin_url is required for a two-person release')
    return dict(schema_version='1', kind='dragonfly-release-approval',
                origin_url=request['origin_url'], release_branch=request['release_branch'],
                release_tag=request['release_tag'], tag_object=oid, commit=request['commit'])


def verify_approval(request, directory):
    directory = Path(directory)
    # Freeze the bytes used for validation and signature verification.
    with (directory / 'release.json').open('rb') as stream:
        payload = stream.read(65537)
    manifest = load_document(None, 'release-approval.schema.json', content=payload)
    with (directory / 'second-signature.asc').open('rb') as stream:
        signature = stream.read(65537)
    if not signature or len(signature) > 65536:
        raise SigningError('approval signature is empty or exceeds 64 KiB')
    with authorized_keyring(minimum=2) as keyring:
        oid, first = verify_tag(request, keyring=keyring)
        if manifest != approval_manifest(request, oid):
            raise SigningError('approval manifest does not match the requested repository, branch, tag object and commit')
        second = verify_detached(payload, signature, keyring)
        if second == first:
            raise SigningError('second approval must use a different authorized primary key')
    return dict(release_tag=request['release_tag'], tag_object=oid, commit=request['commit'],
                tag_signer=first, second_signer=second)
