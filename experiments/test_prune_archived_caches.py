import json
from pathlib import Path
import tempfile
import unittest
from prune_archived_caches import validate, relocate


class ArchiveValidationTest(unittest.TestCase):
    def test_resolved_archive_alias_cannot_be_validated_as_a_scratch_copy(self):
        with tempfile.TemporaryDirectory() as root:
            work, archive = self.roots(root)
            link = Path(root)/'retired-scratch'
            link.symlink_to(archive, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'independent'):
                validate(link, archive)
            with self.assertRaisesRegex(ValueError, 'independent'):
                validate(archive, archive)

    def test_offline_summary_still_requires_identical_archived_material(self):
        with tempfile.TemporaryDirectory() as root:
            work=Path(root)/'work'; archive=Path(root)/'archive'
            for base in (work,archive):
                (base/'paired').mkdir(parents=True)
                (base/'paired/summary.json').write_text('{"status":"failed"}')
            self.assertEqual(validate(work,archive,Path('paired/summary.json')),[])
            for invalid in ('../summary.json','/summary.json'):
                with self.assertRaisesRegex(ValueError,'relative path'):
                    validate(work,archive,Path(invalid))

    def roots(self, root):
        work=Path(root)/'work'; archive=Path(root)/'archive'
        for base in (work,archive):
            p=base/'flow/paired';p.mkdir(parents=True)
            (p/'summary.json').write_text(json.dumps({'status':'completed'}))
        return work,archive

    def test_directory_symlink_must_match(self):
        with tempfile.TemporaryDirectory() as root:
            work,archive=self.roots(root)
            (work/'link').symlink_to('flow',target_is_directory=True)
            with self.assertRaisesRegex(ValueError,'directory link differs'):validate(work,archive)
            (archive/'link').symlink_to('flow',target_is_directory=True)
            self.assertEqual(validate(work,archive),[])

    def test_empty_material_directory_is_required(self):
        with tempfile.TemporaryDirectory() as root:
            work,archive=self.roots(root)
            (work/'empty').mkdir()
            with self.assertRaisesRegex(ValueError,'material directory'):validate(work,archive)
            (archive/'empty').mkdir()
            self.assertEqual(validate(work,archive),[])

    def test_relocation_preserves_old_paths_and_durable_material(self):
        with tempfile.TemporaryDirectory() as root:
            work,archive=self.roots(root)
            for base in (work,archive):
                (base/'evidence').write_text('original witness')
                (base/'link').symlink_to('evidence')
            (work/'csrc').mkdir()
            (work/'csrc'/'cache').write_text('rebuildable')
            relocate(work,archive)
            self.assertTrue(work.is_symlink())
            self.assertEqual((work/'link').read_text(),'original witness')
            self.assertEqual((archive/'evidence').read_text(),'original witness')
            self.assertFalse(list(Path(root).glob('*.retired-*')))

    def test_relocation_rejects_missing_evidence_or_running_summary(self):
        with tempfile.TemporaryDirectory() as root:
            work,archive=self.roots(root)
            (work/'missing').write_text('must not lose')
            with self.assertRaises(ValueError):relocate(work,archive)
            self.assertTrue((work/'missing').exists())
            (archive/'missing').write_text('must not lose')
            for base in (work,archive):
                (base/'flow/paired/summary.json').write_text('{"status":"running"}')
            with self.assertRaisesRegex(ValueError,'running'):relocate(work,archive)
            self.assertFalse(work.is_symlink())


if __name__=='__main__':unittest.main()
