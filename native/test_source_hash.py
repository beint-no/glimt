"""Verify that streaming source checks retain the published canonical hashes."""
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from source_hash import tree_hash


class SourceHashTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.archive = Path(self.temporary.name) / 'source.tar.gz'

    def archive_hash(self, entries, timestamp=0):
        with tarfile.open(self.archive, 'w:gz') as archive:
            for name, kind, mode, link, data in entries:
                member = tarfile.TarInfo(name)
                member.type = kind; member.mode = mode; member.linkname = link
                member.mtime = timestamp
                member.size = len(data) if kind == tarfile.REGTYPE else 0
                archive.addfile(member, io.BytesIO(data) if member.isfile() else None)
        return tree_hash(self.archive)

    def entries(self):
        # Deliberately not in path order; includes empty and large regular files
        # and a hard link preceding its target in one of the tested orders.
        return [
            ['z', tarfile.DIRTYPE, 0o755, '', b''],
            ['z/source', tarfile.REGTYPE, 0o644, '', b'payload' * 200000],
            ['empty', tarfile.REGTYPE, 0o600, '', b''],
            ['symbolic', tarfile.SYMTYPE, 0o777, 'z/source', b''],
            ['hard', tarfile.LNKTYPE, 0o644, 'z/source', b''],
        ]

    def test_matches_published_canonical_record_format(self):
        entries = self.entries()
        records = sorted([[name, kind.decode('ascii'), mode, link,
                           hashlib.sha256(data).hexdigest() if kind == tarfile.REGTYPE else None]
                          for name, kind, mode, link, data in entries], key=lambda entry: entry[0])
        expected = hashlib.sha256(json.dumps(records, separators=(',', ':')).encode()).hexdigest()
        self.assertEqual(expected, self.archive_hash(entries))

    def test_ignores_archive_order_and_timestamps(self):
        entries = self.entries()
        self.assertEqual(self.archive_hash(entries), self.archive_hash(list(reversed(entries)), 123456789))

    def test_authenticates_content_paths_modes_link_targets_and_kinds(self):
        original = self.entries()
        expected = self.archive_hash(original)
        for index, field, changed in [(1, 4, b'changed'), (1, 0, 'renamed'),
                                       (1, 2, 0o755), (3, 3, 'elsewhere'),
                                       (3, 1, tarfile.LNKTYPE)]:
            with self.subTest(index=index, field=field):
                entries = [entry.copy() for entry in original]
                entries[index][field] = changed
                self.assertNotEqual(expected, self.archive_hash(entries))

    def test_rejects_special_files(self):
        for kind in (tarfile.FIFOTYPE, tarfile.CHRTYPE, tarfile.BLKTYPE):
            with self.subTest(kind=kind), self.assertRaisesRegex(RuntimeError, 'Unexpected source archive member'):
                self.archive_hash([['special', kind, 0o600, '', b'']])


if __name__ == '__main__':
    unittest.main()
