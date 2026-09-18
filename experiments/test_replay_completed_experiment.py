import backend_imports
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from replay_completed_experiment import accepted_candidate, compare, check_artifacts
from cycle_replay import digest


class ReproducibilityTests(unittest.TestCase):
    def test_exact_bins_and_native_acceptance_are_required(self):
        reference = dict(bins={'line':[10,9]},percent={'line':90.0},score=90.0,
                         replay=dict(passed=True,native_ltl_sequences=4))
        observed = deepcopy(reference); observed['bins']['line'] = (10,9)
        self.assertTrue(compare(reference,observed)['exact_bins'])
        for key,value in [('bins',{'line':[20,18]}),('percent',{'line':80.0}),
                          ('score',80.0),('replay',dict(passed=True,native_ltl_sequences=3)),
                          ('replay',dict(passed=False,native_ltl_sequences=4))]:
            changed=deepcopy(reference);changed[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError): compare(reference,changed)

    def test_artifact_tampering_is_not_a_repeat(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'data';path.write_text('original')
            coverage={'artifact_sha256':{str(path):digest(path)}}
            check_artifacts(coverage);path.write_text('changed')
            with self.assertRaises(ValueError):check_artifacts(coverage)
        with self.assertRaises(ValueError):check_artifacts({})

    def test_only_the_recorded_accepted_repair_is_replayed(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            def candidate(name,metadata,source):
                path=root/'rvprobe'/name/'candidate.json';path.parent.mkdir(parents=True)
                path.write_text(json.dumps(dict(metadata=metadata,sequences=[source],frames=[])))
            candidate('round-1',{'version':1},'rejected')
            candidate('round-1-repair-1',{'version':2},'accepted')
            summary=dict(status='completed',arms={'rvprobe':dict(status='completed',rounds=[
                dict(round=1,added_sequences=1,metadata={'version':2})])})
            result,sources=accepted_candidate(root,summary)
            self.assertEqual(result['sequences'],['accepted'])
            self.assertEqual(len(sources),1)
            candidate('round-1-repair-2',{'version':2},'ambiguous')
            with self.assertRaises(ValueError):accepted_candidate(root,summary)
            summary['status']='failed'
            with self.assertRaises(ValueError):accepted_candidate(root,summary)


if __name__ == '__main__':unittest.main()
