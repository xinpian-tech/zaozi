import backend_imports
import sys
import unittest
from types import ModuleType
from unittest.mock import patch

from offline_validation import no_model_calls, ModelCallsForbidden
from rvprobe.backend.failures import classify


class OfflineTests(unittest.TestCase):
    def test_cover_failure_after_complete_log_never_requests_model_repair(self):
        text = ('UVM_INFO startup\nRVPROBE_REPLAY_PASS 100\n'
                'UVM_ERROR : 0\nUVM_FATAL : 0\n'
                'original LTL goal did not hold on live IO: arbitrary_goal')
        result = classify(text)
        self.assertEqual(result['kind'], 'formal_replay_semantics_mismatch')
        self.assertFalse(result['model_repair_allowed'])

    def test_legacy_compiled_memory_fallback_is_not_a_valid_sequence(self):
        from haven_shared import check_sequence_set
        source = 'class probe extends uvm_sequence; endclass'
        self.assertEqual(check_sequence_set([source]), ['probe'])
        for marker in ('bus-write fallback, not backdoor', "Skipped memory_write 'buffer'"):
            with self.assertRaisesRegex(ValueError, 'invalid memory_write'):
                check_sequence_set([source+' // '+marker])

    def test_invalid_saved_baseline_stops_before_simulation_or_generation(self):
        from coverage_flow import paired_loop
        from unittest.mock import Mock
        simulator, generator = Mock(), Mock()
        bundle = {'fingerprint':'synthetic', 'sequences':[
            'class probe extends uvm_sequence; endclass // bus-write fallback, not backdoor']}
        with self.assertRaisesRegex(ValueError, 'invalid memory_write'):
            paired_loop(bundle, '/unused', simulator, generator)
        simulator.assert_not_called()
        generator.assert_not_called()

    def test_all_model_boundaries_blocked_and_restored(self):
        import sequence_experiment
        class Client:
            def call(self): return 'original'
            def call_structured(self): return 'original'
        module = ModuleType('haven.utils.llm_client')
        module.LLMClient = Client
        original = sequence_experiment.send_completion
        with patch.dict(sys.modules, {'haven.utils.llm_client': module}):
            with no_model_calls():
                for call in (sequence_experiment.send_completion, Client().call, Client().call_structured):
                    with self.assertRaises(ModelCallsForbidden): call()
            self.assertIs(sequence_experiment.send_completion, original)
            self.assertEqual(Client().call(), 'original')

    def test_only_explicit_fatal_transport_evidence_stops_model_repair(self):
        for tag, kind in [('WITNESS_X','unknown_concrete_output'),
                          ('ENVIRONMENT_WITNESS','environment_response_mismatch'),
                          ('EVENT_TIME','transport_contract_failure')]:
            result = classify(f'UVM_FATAL driver.sv(5) @ 12: driver [{tag}] details')
            self.assertEqual(result['kind'], kind)
            self.assertFalse(result['model_repair_allowed'])
        for text in ('UVM_FATAL driver.sv(5) [WITNESS] mismatch',
                     'UVM_INFO driver [WITNESS_X] expected negative test',
                     '// WITNESS_X', 'UVM_FATAL : 0', 'compile failed'):
            self.assertTrue(classify(text)['model_repair_allowed'])
