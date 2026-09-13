import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock

from offline_saved_flow import SavedWorker, saved_response, verify_design, paired
from cycle_replay import digest
from run_records import save


class OfflineSavedFlowTests(unittest.TestCase):
    def fixture(self, root):
        source = 'Gen(io.valid, "goal")\n'
        save(root/'summary.json', {'attempts':1, 'model':'provider', 'costs':{'tokens':19}})
        save(root/'manifest.json', {'model':'provider', 'design':{}})
        save(root/'attempt-1/provider.json', {'requested_model':'provider','response_status':'complete'})
        (root/'attempt-1/response.txt').write_text(source)
        (root/'attempt-1/sources').mkdir()
        (root/'attempt-1/sources/ModelUT.scala').write_text('generated UT fixture')
        (root/'attempt-1/sources/model.ltl').write_text(source)
        return root

    def test_preserves_raw_ltl_bytes_and_prior_costs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.fixture(Path(tmp))
            result = saved_response(root)
            self.assertEqual(result['response_sha256'], digest(root/'attempt-1/response.txt'))
            self.assertEqual(result['prior_costs'], {'tokens':19})

    def test_modified_ut_and_truncated_response_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.fixture(Path(tmp))
            (root/'attempt-1/sources/model.ltl').write_text('modified')
            with self.assertRaisesRegex(ValueError, 'differs'):
                saved_response(root)
            save(root/'attempt-1/provider.json', {'requested_model':'provider','response_status':'truncated'})
            with self.assertRaisesRegex(ValueError, 'complete'):
                saved_response(root)

    def test_worker_cannot_request_a_second_reply_or_haven_repair(self):
        with tempfile.TemporaryDirectory() as tmp:
            provenance = saved_response(self.fixture(Path(tmp)))
            dispatcher = Mock()
            worker = SavedWorker(provenance, dispatcher)
            command = [sys.executable, str(Path(paired.generation.__file__).resolve())]
            worker(command, timeout=12)
            self.assertEqual(dispatcher.call_args.args[0][-2:], ['--response-file', provenance['response']])
            with self.assertRaisesRegex(ValueError, 'one generation'):
                worker(command)
            with self.assertRaisesRegex(ValueError, 'one generation'):
                SavedWorker(provenance, dispatcher)([sys.executable, str(Path(paired.__file__).resolve()),'request'])

    def test_design_verification_rejects_changed_rtl_or_clock(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp)/'rtl.v'; source.write_text('module dut; endmodule')
            original = dict(top='dut', clock='clk', sources=[dict(path=str(source),sha256=digest(source))], include_files=[])
            verify_design(original, original)
            with self.assertRaisesRegex(ValueError, 'clock'):
                verify_design(original, {**original,'clock':'other'})
            source.write_text('changed RTL')
            with self.assertRaisesRegex(ValueError, 'historical DUT'):
                verify_design(original, original)


if __name__ == '__main__':
    unittest.main()
