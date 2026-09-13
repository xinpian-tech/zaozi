import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import encoded_candidate_search as search


class CandidateSearchTests(unittest.TestCase):
    def frozen(self):
        return object(), {'environment': {'boundary': 'independent-dut-v1'}}, {'fingerprint': 'job'}, [{
            'label': 'intent', 'cycles': 9, 'generationLabel': 'intent', 'utModule': 'OriginalUT',
            'utSourceSha256': 'frozen-sha', 'fingerprint': 'job', 'engine': 'jaspergold'}]

    def test_candidates_keep_original_identity_and_are_not_proofs(self):
        commands=[]
        def run(command, **kwargs):
            commands.append(command)
            out=Path(command[command.index('--out')+1]);out.mkdir()
            (out/'summary.json').write_text(json.dumps({'status':'covered'}))
        with tempfile.TemporaryDirectory() as temp, patch.object(search,'frozen_inputs',return_value=self.frozen()), \
             patch.object(search,'run',side_effect=run), patch.object(search,'import_sample',return_value={
                 'inputFingerprint':'candidate', 'witnessFile':'witness', 'origin':'sampling', 'cycles':12}):
            rows=list(search.candidates(Path('solve'),Path('replay'),'intent',Path(temp)/'search',Path('yosys'),Path('eda'),2,23))
        self.assertEqual(len(rows),2)
        self.assertEqual(rows[0]['utSourceSha256'],'frozen-sha')
        self.assertFalse(rows[0]['original_goal_proven'])
        self.assertEqual(rows[0]['origin'],'encoded-native-candidate-v1')
        self.assertNotIn('--trace-seed',commands[0])
        self.assertNotIn('--trace-cycles',commands[0])
        self.assertEqual(commands[1][-2:],['--trace-seed','24'])
        self.assertEqual(commands[1][commands[1].index('--trace-cycles')+1],'12')
        for command in commands:
            self.assertEqual(command[command.index('--boundary')+1],'frozen-source')

    def test_inconsistent_model_is_not_imported_or_retried(self):
        def run(command, **kwargs):
            out=Path(command[command.index('--out')+1]);out.mkdir()
            (out/'summary.json').write_text(json.dumps({'status':'inconsistent_environment'}))
        with tempfile.TemporaryDirectory() as temp, patch.object(search,'frozen_inputs',return_value=self.frozen()), \
             patch.object(search,'run',side_effect=run) as solver, patch.object(search,'import_sample') as importer:
            rows=list(search.candidates(Path('solve'),Path('replay'),'intent',Path(temp)/'search',Path('yosys'),Path('eda'),4,23))
            self.assertEqual(rows,[])
            self.assertEqual(solver.call_count,1)
            importer.assert_not_called()

    def test_duplicate_enables_subset_and_failed_subset_consumes_same_budget(self):
        commands=[]
        def run(command, **kwargs):
            commands.append(command)
            out=Path(command[command.index('--out')+1]);out.mkdir()
            status='no_distinct_candidate' if len(commands)==3 else 'covered'
            (out/'summary.json').write_text(json.dumps({'status':status}))
        samples=[{'inputFingerprint':fp,'cycles':12} for fp in ('a','a','b')]
        with tempfile.TemporaryDirectory() as temp, patch.object(search,'frozen_inputs',return_value=self.frozen()), \
             patch.object(search,'run',side_effect=run), patch.object(search,'import_sample',side_effect=samples) as importer:
            rows=list(search.candidates(Path('solve'),Path('replay'),'intent',Path(temp)/'search',Path('yosys'),Path('eda'),4,23))
            self.assertEqual(len(commands),4)
            self.assertEqual(importer.call_count,3)
            self.assertEqual([r['inputFingerprint'] for r in rows],['a','a','b'])
            self.assertNotIn('--avoid-stimulus',commands[1])
            self.assertIn('--avoid-stimulus',commands[2])
            self.assertIn('--avoid-stimulus',commands[3])


if __name__=='__main__':unittest.main()
