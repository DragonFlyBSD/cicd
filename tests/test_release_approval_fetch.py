"""Approval branch content is read as bounded data, never checked out as tools."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ApprovalFetchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='approval-fetch-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.seed = self.root / 'seed'
        self.seed.mkdir()
        self.env = dict(os.environ, GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
        self.git(self.seed, 'init', '-b', 'master')
        self.git(self.seed, 'config', 'user.name', 'Approval Test')
        self.git(self.seed, 'config', 'user.email', 'approval@example.invalid')
        (self.seed / 'README').write_text('Public approval data\n')
        self.commit()
        self.bare = self.root / 'approval-data.git'
        self.git(self.root, 'init', '--bare', str(self.bare))
        self.git(self.bare, 'remote', 'add', 'origin', self.seed.as_uri())
        self.directory = self.seed / 'approvals' / 'v6.6.0'
        self.directory.mkdir(parents=True)
        self.output = self.root / 'output'

    def git(self, cwd, *arguments):
        return subprocess.run(['git', *arguments], cwd=cwd, env=self.env,
                              capture_output=True, check=True).stdout.decode().strip()

    def commit(self):
        self.git(self.seed, 'add', '.')
        self.git(self.seed, '-c', 'commit.gpgsign=false', 'commit', '-m', 'Publish approval data')

    def pair(self):
        (self.directory / 'release.json').write_text('{}\n')
        (self.directory / 'second-signature.asc').write_text('signature bytes\n')

    def fetch(self, expected=0):
        result = subprocess.run([
            sys.executable, str(ROOT / 'scripts/fetch-release-approval'), '--git-dir', str(self.bare),
            '--tag', 'v6.6.0', '--output', str(self.output)
        ], env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode, expected, result.stderr)
        return result

    def test_missing_and_partial_approvals_wait(self):
        self.fetch(2)
        (self.directory / 'release.json').write_text('{}\n')
        self.commit()
        self.fetch(2)
        self.assertFalse(self.output.exists())

    def test_extracts_only_data_at_one_recorded_revision(self):
        self.pair()
        scripts = self.seed / 'scripts'
        scripts.mkdir()
        marker = self.root / 'executed-untrusted-code'
        (scripts / 'verify-release-approval').write_text(f'#!/bin/sh\ntouch "{marker}"\n')
        (scripts / 'verify-release-approval').chmod(0o755)
        self.commit()
        self.fetch()
        self.assertEqual({path.name for path in self.output.iterdir()},
                         {'release.json', 'second-signature.asc', 'source-commit.txt'})
        self.assertEqual((self.output / 'source-commit.txt').read_text().strip(), self.git(self.seed, 'rev-parse', 'HEAD'))
        self.assertFalse(marker.exists())
        (self.directory / 'release.json').write_text('{"new":true}\n')
        self.commit()
        self.fetch()
        self.assertEqual((self.output / 'release.json').read_text(), '{"new":true}\n')

    def test_fetch_failure_is_not_a_missing_approval(self):
        self.git(self.bare, 'remote', 'set-url', 'origin', str(self.root / 'does-not-exist'))
        self.fetch(1)

    def test_symlink_is_rejected(self):
        self.pair()
        (self.directory / 'release.json').unlink()
        (self.directory / 'release.json').symlink_to('../../README')
        self.commit()
        self.assertIn('regular non-executable', self.fetch(1).stderr)

    def test_executable_approval_is_rejected(self):
        self.pair()
        (self.directory / 'second-signature.asc').chmod(0o755)
        self.commit()
        self.fetch(1)

    def test_oversized_approval_is_rejected(self):
        self.pair()
        (self.directory / 'release.json').write_bytes(b'x' * 65537)
        self.commit()
        self.assertIn('64 KiB', self.fetch(1).stderr)
