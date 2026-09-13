import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from refresh_haven_component_patch import refresh


class PatchExportTests(unittest.TestCase):
    def test_preserves_original_missing_final_newline(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); before=root/'before'; after=root/'after'
            before.mkdir(); after.mkdir()
            raw=b'{"bfm_configs":null}'
            (before/'config.json').write_bytes(raw)
            (after/'config.json').write_text('{"bfm_configs":[]}\n')
            manifest=root/'manifest.json'
            manifest.write_text(json.dumps({'files':{'config.json':{
                'before':hashlib.sha256(raw).hexdigest()}}}))
            patch=root/'portable.patch'
            self.assertTrue(refresh(after,before,manifest,patch)['forward_and_reverse_check'])
            self.assertIn('\\ No newline at end of file',patch.read_text())
            self.assertEqual((before/'config.json').read_bytes(),raw)


if __name__=='__main__': unittest.main()
