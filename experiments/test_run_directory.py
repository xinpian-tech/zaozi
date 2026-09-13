import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from run_records import fresh_directory


class FreshDirectoryTests(unittest.TestCase):
    def test_never_reuses_existing_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            target=Path(tmp)/'run'
            fresh_directory(target)
            before=(target/'.rvprobe-directory-owner').read_text()
            with self.assertRaises(FileExistsError): fresh_directory(target)
            self.assertEqual((target/'.rvprobe-directory-owner').read_text(),before)
            empty=Path(tmp)/'empty'; empty.mkdir()
            with self.assertRaises(FileExistsError): fresh_directory(empty)
            link=Path(tmp)/'link'; link.symlink_to(Path(tmp)/'absent')
            with self.assertRaises(FileExistsError): fresh_directory(link)

    def test_recovers_only_empty_create_with_exclusive_claim(self):
        with tempfile.TemporaryDirectory() as tmp:
            target=Path(tmp)/'run'
            original=Path.mkdir
            def create_then_error(path,*a,**kw):
                result=original(path,*a,**kw)
                if path==target: raise FileExistsError('simulated lost mkdir reply')
                return result
            with patch.object(Path,'mkdir',create_then_error): fresh_directory(target)
            record=json.loads((target/'.rvprobe-directory-owner').read_text())
            self.assertTrue(record['recovered_empty_create'])

    def test_conflicting_artifact_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            target=Path(tmp)/'run'
            original=Path.mkdir
            def race(path,*a,**kw):
                result=original(path,*a,**kw)
                if path==target:
                    (path/'existing').write_text('retain')
                    raise FileExistsError('another writer')
                return result
            with patch.object(Path,'mkdir',race), self.assertRaises(FileExistsError):
                fresh_directory(target)
            self.assertEqual((target/'existing').read_text(),'retain')
            self.assertFalse((target/'.rvprobe-directory-owner').exists())
