from copy import deepcopy
import unittest
from pathlib import Path
import tempfile
from unittest.mock import patch
from haven_design_batch import pilot_health
from evidence_packet import POLICY
from stability_campaign import assess


class StabilityGateTests(unittest.TestCase):
    def setUp(self):
        row=dict(status='completed',accounting_complete=True,requests=4,recorded_calls=4,
            unique_call_ids=4,tokens=100,summed_tokens=100,seconds=30,coverage=95,baseline=85,
            added_sequences=4,requested_models=['deepseek-v4-flash-vision-exp'],
            dialogue_policies=[POLICY],reasoning_history_characters=0,all_intents_satisfied=False)
        self.repeats=[dict(uart=deepcopy(row),ethmac=deepcopy(row)) for _ in range(2)]
        self.refs={name:dict(tokens=200,coverage=95) for name in ('uart','ethmac')}

    def test_native_valid_subsets_can_pass_without_claiming_all_intents(self):
        self.assertTrue(assess(self.repeats,self.refs)['passed'])

    def test_framework_accounting_and_dialogue_fail_closed(self):
        for change in ({'status':'failed'},{'accounting_complete':False},{'unique_call_ids':3},
                       {'summed_tokens':99},{'requested_models':['other']},
                       {'dialogue_policies':['old']},{'reasoning_history_characters':10},
                       {'added_sequences':0},{'coverage':85}):
            rows=deepcopy(self.repeats);rows[0]['ethmac'].update(change)
            with self.subTest(change=change):self.assertFalse(assess(rows,self.refs)['passed'])

    def test_token_reduction_is_not_enough_if_coverage_regresses(self):
        self.repeats[0]['ethmac']['coverage']=92
        self.assertFalse(assess(self.repeats,self.refs)['passed'])

    def test_unstable_tokens_or_wall_time_block_full_run(self):
        for change in ({'tokens':160,'summed_tokens':160},{'seconds':60}):
            rows=deepcopy(self.repeats);rows[1]['uart'].update(change)
            self.assertFalse(assess(rows,self.refs)['passed'])

    def test_reference_token_gate_requires_ethmac_improvement(self):
        for row in self.repeats:
            row['ethmac'].update(tokens=190,summed_tokens=190)
        self.assertFalse(assess(self.repeats,self.refs)['passed'])

    def test_failed_pilot_signals_peers_without_overwriting_first_cause(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);flag=root/'stop.json'
            report=pilot_health(root/'uart',root/'ref','uart','failed',flag)
            self.assertFalse(report['passed']);first=flag.read_text()
            pilot_health(root/'ethmac',root/'ref','ethmac','failed',flag)
            self.assertEqual(flag.read_text(),first)

    def test_healthy_single_pilot_does_not_stop_its_peer(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);flag=root/'stop.json'
            with patch('stability_campaign.metrics',side_effect=[self.repeats[0]['uart'],self.refs['uart']]):
                report=pilot_health(root/'uart',root/'ref','uart','completed',flag)
            self.assertTrue(report['passed']);self.assertFalse(flag.exists())


if __name__=='__main__':unittest.main()
