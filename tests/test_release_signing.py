"""Real Git/OpenPGP checks: python -m unittest discover -s tests -p test_release_signing.py -v."""

import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))


class ReleaseSigningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        for binary in ('git', 'gpg', 'gpgconf'):
            if not shutil.which(binary):
                raise unittest.SkipTest(f'{binary} is required for signing integration tests')
        # A short path also fits GPG-agent Unix socket limits on macOS.
        cls.directory = tempfile.TemporaryDirectory(prefix='dfsig-', dir='/tmp')
        cls.root = Path(cls.directory.name)
        cls.home = cls.root / 'gpg'
        cls.home.mkdir(mode=0o700)
        cls.env = dict(os.environ, GNUPGHOME=str(cls.home), GIT_CONFIG_NOSYSTEM='1',
                       GIT_CONFIG_GLOBAL=os.devnull)
        cls.addClassCleanup(cls.directory.cleanup)
        cls.addClassCleanup(subprocess.run,
                            ['gpgconf', '--homedir', str(cls.home), '--kill', 'all'],
                            env=cls.env, capture_output=True)
        cls.gpg('--quick-generate-key', 'Authorized Test <authorized@example.invalid>', 'ed25519', 'cert', '0')
        cls.authorized = cls.fingerprint()
        cls.gpg('--quick-add-key', cls.authorized, 'ed25519', 'sign', '0')
        cls.gpg('--quick-add-key', cls.authorized, 'ed25519', 'sign', '0')
        cls.signing_subkeys = [line.split(':')[9] for line in
                               cls.gpg('--with-colons', '--list-keys', cls.authorized).stdout.decode().splitlines()
                               if line.startswith('fpr:')][1:]
        cls.gpg('--quick-generate-key', 'Other Test <other@example.invalid>', 'ed25519', 'sign', '0')
        listing = cls.gpg('--with-colons', '--list-keys').stdout.decode()
        fingerprints = []
        primary = False
        for line in listing.splitlines():
            fields = line.split(':')
            if fields[0] in ('pub', 'sub'):
                primary = fields[0] == 'pub'
            elif fields[0] == 'fpr' and primary:
                fingerprints.append(fields[9])
                primary = False
        cls.other = next(value for value in fingerprints if value != cls.authorized)
        cls.public = cls.gpg('--armor', '--export', cls.authorized).stdout

    @classmethod
    def gpg(cls, *arguments):
        return subprocess.run(
            ['gpg', '--no-options', '--batch', '--pinentry-mode', 'loopback', '--passphrase', '', *arguments],
            env=cls.env, check=True, capture_output=True,
        )

    @classmethod
    def fingerprint(cls):
        listing = cls.gpg('--with-colons', '--list-keys').stdout.decode()
        return next(line.split(':')[9] for line in listing.splitlines() if line.startswith('fpr:'))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='case-', dir=self.root)
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / 'source tree'
        self.repo.mkdir()
        self.keys = Path(self.temp.name) / 'authorized keys'
        self.keys.mkdir()
        (self.keys / 'authorized.asc').write_bytes(self.public)
        self.run_git('init', '-b', 'master')
        self.run_git('config', 'user.name', 'Release Test')
        self.run_git('config', 'user.email', 'release@example.invalid')
        self.run_git('config', 'user.signingkey', self.authorized)
        self.run_git('config', 'gpg.program', shutil.which('gpg'))
        (self.repo / 'source').write_text('Reviewed source\n')
        self.run_git('add', '.')
        self.run_git('-c', 'commit.gpgsign=false', 'commit', '-m', 'Reviewed commit')
        self.commit = self.run_git('rev-parse', 'HEAD').stdout.decode().strip()
        self.request = dict(schema_version='1', release_tag='v6.6.0', release_branch='DragonFly_RELEASE_6_6',
                            target_version='6.6.0', release_stage='final', commit=self.commit,
                            tag_message='DragonFly 6.6.0')
        self.path = self.repo / 'signing request.json'
        self.origin = Path(self.temp.name) / 'origin.git'
        self.run_git('init', '--bare', str(self.origin))
        self.run_git('remote', 'add', 'origin', self.origin.as_uri())
        self.run_git('push', 'origin', f'HEAD:refs/heads/{self.request["release_branch"]}')
        self.request['origin_url'] = self.origin.as_uri()


    def run_git(self, *arguments, input=None):
        return subprocess.run(['git', *arguments], cwd=self.repo, env=self.env,
                              input=input, capture_output=True, check=True)

    def cli(self, script, request=None, *, raw=None, succeeds=True, contains=None, extra=(), confirmation=None):
        if raw is not None:
            self.path.write_text(raw)
        else:
            self.path.write_text(json.dumps(self.request if request is None else request))
        env = dict(self.env, AUTHORIZED_SIGNERS_DIR=str(self.keys))
        arguments = [sys.executable, str(SCRIPTS / script)]
        if script == 'sign-release-tag':
            arguments += ['--key', self.authorized, '--origin', self.origin.as_uri()]
        arguments.append(str(self.path))
        arguments.extend(extra)
        effective = self.request if request is None else request
        result = subprocess.run(arguments, input=(effective.get('release_tag', '') if confirmation is None else confirmation) + '\n',
                                cwd=self.repo, env=env, capture_output=True, text=True)
        if succeeds:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        if contains:
            self.assertIn(contains, result.stdout + result.stderr)
        return result

    def signed_tag(self, name='v6.6.0', commit=None, key=None):
        self.run_git('-c', 'gpg.format=openpgp', 'tag', '-s', '-u', key or self.authorized,
                     '-m', f'DragonFly {name[1:]}', name, commit or self.commit)

    def test_final_signed_by_authorized_subkey(self):
        self.cli('sign-release-tag')
        self.cli('verify-release-tag', contains=f'signer {self.authorized}')

    def test_rc_signed_and_verified(self):
        self.request.update(release_stage='rc', rc_number='2', release_tag='v6.6.0rc2',
                            tag_message='DragonFly 6.6.0rc2')
        self.cli('sign-release-tag')
        self.cli('verify-release-tag')

    def test_authorized_primary_signing_key(self):
        (self.keys / 'authorized.asc').write_bytes(self.gpg('--armor', '--export', self.other).stdout)
        self.signed_tag(key=self.other)
        self.cli('verify-release-tag', contains=f'signer {self.other}')

    def test_valid_json_escaping_and_optional_metadata(self):
        self.request.update(jenkins_build='release "test"', created='2026-11-16T00:00:00Z')
        self.cli('validate-release-signing-request')
        self.cli('sign-release-tag')
        self.cli('verify-release-tag')

    def test_reject_malformed_and_duplicate_json_before_git(self):
        for raw in ('{', '[]', 'null', '{}', '{"schema_version":"1","schema_version":"2"}',
                    '{"field": NaN}'):
            for script in ('sign-release-tag', 'verify-release-tag'):
                with self.subTest(raw=raw, script=script):
                    self.cli(script, raw=raw, succeeds=False)
        self.assertEqual(self.run_git('tag', '--list').stdout, b'')

    def test_reject_schema_and_cross_field_errors_before_signing_or_verifying(self):
        cases = [
            {'schema_version':'2'}, {'commit':'HEAD'}, {'commit':'a'*39}, {'commit':1},
            {'unexpected':'value'}, {'release_stage':'preview'}, {'rc_number':'1'},
            {'release_stage':'rc'}, {'release_stage':'rc', 'rc_number':'0'},
            {'release_branch':'DragonFly_RELEASE_6_4'}, {'target_version':'6.6.1'},
            {'release_tag':'v6.6.0rc1'}, {'release_tag':'-uattacker'},
            {'release_tag':'v6.6.0\n'}, {'tag_message':'DragonFly 6.6.0\x00'},
            {'tag_message':'Unrelated release'}, {'tag_message':None},
            {'target_version':'6.06.0'}, {'release_branch':'DragonFly_RELEASE_6_06'},
            {'release_stage':'rc', 'rc_number':'2', 'release_tag':'v6.6.0rc1'},
            {'release_branch':'DragonFly_RELEASE_6_5', 'target_version':'6.5.0',
             'release_tag':'v6.5.0', 'tag_message':'DragonFly 6.5.0'},
        ]
        for updates in cases:
            request = dict(self.request, **updates)
            for script in ('sign-release-tag', 'verify-release-tag'):
                with self.subTest(updates=updates, script=script):
                    self.cli(script, request, succeeds=False)
        for field in self.request:
            request = copy.copy(self.request)
            del request[field]
            with self.subTest(missing=field):
                self.cli('sign-release-tag', request, succeeds=False)

    def test_reject_lightweight_tag(self):
        self.run_git('tag', 'v6.6.0', self.commit)
        self.cli('verify-release-tag', succeeds=False, contains='annotated, signed')

    def test_reject_unsigned_annotated_tag(self):
        self.run_git('tag', '-a', '-m', 'DragonFly 6.6.0', 'v6.6.0', self.commit)
        self.cli('verify-release-tag', succeeds=False, contains='signature did not verify')

    def test_reject_unauthorized_key_even_if_in_users_keyring(self):
        self.signed_tag(key=self.other)
        self.cli('verify-release-tag', succeeds=False, contains='signature did not verify')

    def test_reject_empty_or_broken_allowlist(self):
        self.signed_tag()
        (self.keys / 'authorized.asc').unlink()
        self.cli('verify-release-tag', succeeds=False, contains='no authorized signer')
        (self.keys / 'broken.asc').write_text('not a public key')
        self.cli('verify-release-tag', succeeds=False, contains='gpg failed')

    def test_reject_wrong_commit(self):
        self.signed_tag()
        (self.repo / 'source').write_text('A different commit\n')
        self.run_git('add', '.')
        self.run_git('-c', 'commit.gpgsign=false', 'commit', '-m', 'Other commit')
        request = dict(self.request, commit=self.run_git('rev-parse', 'HEAD').stdout.decode().strip())
        self.cli('verify-release-tag', request, succeeds=False, contains='exact requested commit')

    def test_reject_signed_tag_reused_under_another_ref(self):
        self.signed_tag(name='v6.6.0rc1')
        oid = self.run_git('rev-parse', 'refs/tags/v6.6.0rc1').stdout.decode().strip()
        self.run_git('update-ref', 'refs/tags/v6.6.0', oid)
        self.cli('verify-release-tag', succeeds=False, contains='internal tag name')

    def test_reject_nested_tag_even_if_it_peels_to_requested_commit(self):
        self.signed_tag(name='v6.6.0rc1')
        self.run_git('tag', '-s', '-m', 'DragonFly 6.6.0', 'v6.6.0', 'v6.6.0rc1')
        self.cli('verify-release-tag', succeeds=False, contains='point directly')

    def test_reject_signature_after_object_tampering(self):
        self.signed_tag()
        obj = self.run_git('cat-file', 'tag', 'refs/tags/v6.6.0').stdout
        obj = obj.replace(b'DragonFly 6.6.0\n', b'Tampered release\n')
        oid = self.run_git('hash-object', '-t', 'tag', '-w', '--stdin', input=obj).stdout.decode().strip()
        self.run_git('update-ref', 'refs/tags/v6.6.0', oid)
        self.cli('verify-release-tag', succeeds=False, contains='signature did not verify')

    def test_reject_expired_signing_key(self):
        identity = 'Expired Test <expired@example.invalid>'
        self.gpg('--faked-system-time', '20200101T000000', '--quick-generate-key',
                 identity, 'ed25519', 'sign', '1d')
        listing = self.gpg('--with-colons', '--list-keys', identity).stdout.decode()
        fingerprint = next(line.split(':')[9] for line in listing.splitlines() if line.startswith('fpr:'))
        (self.keys / 'authorized.asc').write_bytes(self.gpg('--armor', '--export', fingerprint).stdout)
        payload = (f'object {self.commit}\ntype commit\ntag v6.6.0\n'
                   'tagger Expired Test <expired@example.invalid> 1577840400 +0000\n\n'
                   'DragonFly 6.6.0\n').encode()
        unsigned = self.repo / 'tag-payload'
        signature = self.repo / 'tag-signature.asc'
        unsigned.write_bytes(payload)
        self.gpg('--faked-system-time', '20200101T010000', '--local-user', fingerprint,
                 '--armor', '--detach-sign', '--output', str(signature), str(unsigned))
        oid = self.run_git('hash-object', '-t', 'tag', '-w', '--stdin',
                           input=payload + signature.read_bytes()).stdout.decode().strip()
        self.run_git('update-ref', 'refs/tags/v6.6.0', oid)
        self.cli('verify-release-tag', succeeds=False)

    def test_reject_revoked_authorized_key(self):
        self.signed_tag(key=self.other)
        certificate = (self.home / 'openpgp-revocs.d' / f'{self.other}.rev').read_text()
        certificate = certificate[certificate.index('-----BEGIN PGP PUBLIC KEY BLOCK-----'):]
        public = self.gpg('--armor', '--export', self.other).stdout
        # Import revocation only into the verifier's temporary keyring, leaving
        # the shared disposable signing keyring usable by the other tests.
        (self.keys / 'authorized.asc').write_bytes(public + certificate.encode())
        self.cli('verify-release-tag', succeeds=False)

    def test_reject_non_commit_request_object(self):
        blob = self.run_git('hash-object', 'source').stdout.decode().strip()
        self.cli('sign-release-tag', dict(self.request, commit=blob), succeeds=False, contains='remote release branch tip')
        self.cli('verify-release-tag', dict(self.request, commit=blob), succeeds=False, contains='not a commit')

    def test_reject_oversized_request(self):
        self.cli('sign-release-tag', raw=' ' * 65537, succeeds=False, contains='exceeds 64 KiB')

    def test_replacement_object_cannot_supply_missing_signature(self):
        self.signed_tag()
        signed_oid = self.run_git('rev-parse', 'refs/tags/v6.6.0').stdout.decode().strip()
        obj = self.run_git('cat-file', 'tag', signed_oid).stdout
        unsigned = obj.split(b'-----BEGIN PGP SIGNATURE-----')[0]
        unsigned_oid = self.run_git('hash-object', '-t', 'tag', '-w', '--stdin', input=unsigned).stdout.decode().strip()
        self.run_git('update-ref', 'refs/tags/v6.6.0', unsigned_oid)
        self.run_git('replace', unsigned_oid, signed_oid)
        self.cli('verify-release-tag', succeeds=False, contains='signature did not verify')

    def test_signer_never_replaces_existing_tag(self):
        self.signed_tag()
        oid = self.run_git('rev-parse', 'refs/tags/v6.6.0').stdout
        self.cli('sign-release-tag', succeeds=False, contains='already exists')
        self.assertEqual(self.run_git('rev-parse', 'refs/tags/v6.6.0').stdout, oid)

    def test_verifier_ignores_repository_signature_program_override(self):
        self.signed_tag()
        self.run_git('config', 'gpg.program', '/definitely/not/a/real/program')
        self.run_git('config', 'gpg.openpgp.program', '/definitely/not/a/real/program')
        self.cli('verify-release-tag')

    def test_missing_schema_fails_closed(self):
        # Exercise a packaged tools directory with its schema absent.
        tools = Path(self.temp.name) / 'tools' / 'scripts'
        tools.mkdir(parents=True)
        for name in ('release_signing.py', 'validate-release-signing-request'):
            shutil.copyfile(SCRIPTS / name, tools / name)
        self.path.write_text(json.dumps(self.request))
        result = subprocess.run([sys.executable, str(tools / 'validate-release-signing-request'), str(self.path)],
                                cwd=self.repo, env=self.env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('cannot read signing request', result.stderr)

    def prepare_two_person_release(self):
        (self.keys / 'second.asc').write_bytes(self.gpg('--armor', '--export', self.other).stdout)
        self.cli('sign-release-tag')
        self.run_git('push', 'origin', 'refs/tags/v6.6.0')
        self.approvals = Path(self.temp.name) / 'approvals'
        self.approval = self.approvals / 'v6.6.0'

    def approve(self, key=None, **kwargs):
        return self.cli('approve-release', extra=[
            '--key', key or self.other, '--origin', self.origin.as_uri(),
            '--output-dir', str(self.approvals)], **kwargs)

    def verify_pair(self, **kwargs):
        return self.cli('verify-release-approval', extra=[str(self.approval)], **kwargs)

    def resign_approval(self, key=None):
        self.gpg('--yes', '--armor', '--detach-sign', '--local-user', key or self.other,
                 '--output', str(self.approval / 'second-signature.asc'), str(self.approval / 'release.json'))

    def test_two_authorized_people_approve_same_tag(self):
        self.prepare_two_person_release()
        self.approve()
        result = self.verify_pair()
        self.assertIn(self.authorized, result.stdout)
        self.assertIn(self.other, result.stdout)
        manifest = json.loads((self.approval / 'release.json').read_text())
        self.assertEqual(manifest['tag_object'], self.run_git('rev-parse', 'refs/tags/v6.6.0').stdout.decode().strip())
        self.assertEqual(manifest['origin_url'], self.origin.as_uri())

    def test_second_approver_cannot_use_first_primary_key(self):
        self.prepare_two_person_release()
        self.approve(key=self.authorized, succeeds=False, contains='different authorized primary key')
        self.assertFalse(self.approval.exists())

    def test_verifier_rejects_second_signature_from_first_primary_key(self):
        self.prepare_two_person_release()
        self.approve()
        self.resign_approval(self.authorized)
        self.verify_pair(succeeds=False, contains='different authorized primary key')

    def test_two_subkeys_of_one_primary_do_not_count_as_two_signers(self):
        (self.keys / 'second.asc').write_bytes(self.gpg('--armor', '--export', self.other).stdout)
        self.assertEqual(len(self.signing_subkeys), 2)
        self.signed_tag(key=self.signing_subkeys[0] + '!')
        self.run_git('push', 'origin', 'refs/tags/v6.6.0')
        self.approvals = Path(self.temp.name) / 'approvals'
        self.approval = self.approvals / 'v6.6.0'
        self.approve()
        self.resign_approval(self.signing_subkeys[1] + '!')
        self.verify_pair(succeeds=False, contains='different authorized primary key')

    def test_valid_signature_cannot_approve_another_release_or_repository(self):
        self.prepare_two_person_release()
        self.approve()
        original = json.loads((self.approval / 'release.json').read_text())
        for field, value in [('origin_url', 'https://other.invalid/source'),
                             ('release_branch', 'DragonFly_RELEASE_6_4'),
                             ('release_tag', 'v6.4.0'), ('commit', 'a'*40), ('tag_object', 'b'*40)]:
            with self.subTest(field=field):
                (self.approval / 'release.json').write_text(json.dumps(dict(original, **{field:value})))
                self.resign_approval()
                self.verify_pair(succeeds=False, contains='manifest does not match')

    def test_approval_rejects_tampered_signature_and_missing_files(self):
        self.prepare_two_person_release()
        self.approve()
        signature = self.approval / 'second-signature.asc'
        signature.write_bytes(signature.read_bytes().replace(b'BEGIN PGP', b'BEGIN XXX'))
        self.verify_pair(succeeds=False, contains='signature did not verify')
        signature.unlink()
        self.verify_pair(succeeds=False)

    def test_approval_schema_and_duplicate_fields_are_enforced(self):
        self.prepare_two_person_release()
        self.approve()
        original = json.loads((self.approval / 'release.json').read_text())
        for updates in ({'schema_version':'2'}, {'kind':'unrelated-approval'}, {'unknown':'field'}):
            with self.subTest(updates=updates):
                (self.approval / 'release.json').write_text(json.dumps(dict(original, **updates)))
                self.resign_approval()
                self.verify_pair(succeeds=False, contains='schema')
        (self.approval / 'release.json').write_text('{"schema_version":"1","schema_version":"1"}')
        self.resign_approval()
        self.verify_pair(succeeds=False, contains='duplicate JSON field')

    def test_unauthorized_second_signer_is_rejected(self):
        self.prepare_two_person_release()
        self.approve()
        identity = 'Outsider Test <outsider@example.invalid>'
        self.gpg('--quick-generate-key', identity, 'ed25519', 'sign', '0')
        listing = self.gpg('--with-colons', '--list-keys', identity).stdout.decode()
        outsider = next(line.split(':')[9] for line in listing.splitlines() if line.startswith('fpr:'))
        self.resign_approval(outsider)
        self.verify_pair(succeeds=False, contains='signature did not verify')

    def test_second_approver_does_not_overwrite_approval(self):
        self.prepare_two_person_release()
        self.approve()
        before = (self.approval / 'second-signature.asc').read_bytes()
        self.approve(succeeds=False, contains='already exists')
        self.assertEqual((self.approval / 'second-signature.asc').read_bytes(), before)

    def test_second_approver_uses_remote_tag_without_overwriting_local_tag(self):
        self.prepare_two_person_release()
        remote_oid = self.run_git('rev-parse', 'refs/tags/v6.6.0').stdout.decode().strip()
        self.signed_tag(name='v6.4.9')
        stale_oid = self.run_git('rev-parse', 'refs/tags/v6.4.9').stdout.decode().strip()
        self.run_git('update-ref', 'refs/tags/v6.6.0', stale_oid)
        self.approve()
        self.assertEqual(json.loads((self.approval / 'release.json').read_text())['tag_object'], remote_oid)
        self.assertEqual(self.run_git('rev-parse', 'refs/tags/v6.6.0').stdout.decode().strip(), stale_oid)

    def test_signer_requires_exact_confirmation(self):
        self.cli('sign-release-tag', confirmation='no', succeeds=False, contains='confirmation did not match')
        self.assertEqual(self.run_git('tag', '--list').stdout, b'')

    def test_signer_requires_explicit_key_and_expected_origin(self):
        self.path.write_text(json.dumps(self.request))
        result = subprocess.run([sys.executable, str(SCRIPTS / 'sign-release-tag'), str(self.path)],
                                cwd=self.repo, env=self.env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('--key', result.stderr)
        self.assertIn('--origin', result.stderr)

    def test_signer_rejects_request_or_local_origin_mismatch(self):
        self.cli('sign-release-tag', dict(self.request, origin_url='https://other.invalid/source'),
                 succeeds=False, contains='explicitly expected --origin')
        self.run_git('remote', 'set-url', 'origin', 'https://other.invalid/source')
        self.cli('sign-release-tag', succeeds=False, contains='local origin')

    def test_signer_rejects_wrong_remote_release_tip(self):
        self.run_git('-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-m', 'New tip')
        self.run_git('push', 'origin', 'HEAD:refs/heads/DragonFly_RELEASE_6_6')
        self.cli('sign-release-tag', succeeds=False, contains='remote release branch tip')

    def test_subkey_fingerprint_is_not_accepted_as_explicit_primary(self):
        self.cli('sign-release-tag', extra=['--key', self.signing_subkeys[0]],
                 succeeds=False, contains='not a subkey')

    def test_branch_change_during_confirmation_prevents_signing(self):
        self.path.write_text(json.dumps(self.request))
        env = dict(self.env, AUTHORIZED_SIGNERS_DIR=str(self.keys))
        process = subprocess.Popen([sys.executable, str(SCRIPTS / 'sign-release-tag'),
            '--key', self.authorized, '--origin', self.origin.as_uri(), str(self.path)],
            cwd=self.repo, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True)
        try:
            seen = ''
            while 'to sign this exact release:' not in seen and len(seen) < 20000:
                character = process.stdout.read(1)
                if not character:
                    break
                seen += character
            self.assertIn('to sign this exact release:', seen)
            self.run_git('-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-m', 'Concurrent update')
            self.run_git('push', 'origin', 'HEAD:refs/heads/DragonFly_RELEASE_6_6')
            _, errors = process.communicate('v6.6.0\n', timeout=10)
            self.assertNotEqual(process.returncode, 0)
            self.assertIn('remote release branch tip', errors)
            self.assertEqual(self.run_git('tag', '--list').stdout, b'')
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()

    def test_review_escapes_terminal_control_characters(self):
        self.run_git('-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-m', '\x1b[31mUntrusted title')
        self.commit = self.run_git('rev-parse', 'HEAD').stdout.decode().strip()
        self.request['commit'] = self.commit
        self.run_git('push', 'origin', 'HEAD:refs/heads/DragonFly_RELEASE_6_6')
        result = self.cli('sign-release-tag')
        self.assertNotIn('\x1b', result.stdout)
        self.assertIn('\\x1b', result.stdout)

    def test_previous_release_summary(self):
        self.run_git('tag', 'v6.4.2', self.commit)
        self.run_git('tag', 'v6.5.0', self.commit)
        self.run_git('push', 'origin', 'refs/tags/v6.4.2', 'refs/tags/v6.5.0')
        (self.repo / 'source').write_text('New release source\n')
        self.run_git('add', 'source')
        self.run_git('-c', 'commit.gpgsign=false', 'commit', '-m', 'Release changes')
        self.commit = self.run_git('rev-parse', 'HEAD').stdout.decode().strip()
        self.request['commit'] = self.commit
        self.run_git('push', 'origin', 'HEAD:refs/heads/DragonFly_RELEASE_6_6')
        self.cli('sign-release-tag', extra=['--previous-tag', 'v6.5.0'],
                 succeeds=False, contains='earlier final release')
        self.cli('sign-release-tag', contains='Comparison release: v6.4.2')

    def test_policy_counts_primary_keys_not_files_or_subkeys(self):
        (self.keys / 'duplicate.asc').write_bytes(self.public)
        env = dict(self.env, AUTHORIZED_SIGNERS_DIR=str(self.keys))
        result = subprocess.run([sys.executable, str(SCRIPTS / 'check-release-signing-policy')],
                                cwd=self.repo, env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('2 distinct authorized primary keys', result.stderr)
        (self.keys / 'second.asc').write_bytes(self.gpg('--armor', '--export', self.other).stdout)
        result = subprocess.run([sys.executable, str(SCRIPTS / 'check-release-signing-policy')],
                                cwd=self.repo, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
