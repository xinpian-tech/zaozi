import json
from pathlib import Path
import tempfile
import unittest

from analyze_token_cost import analyze


class TokenCostAnalysisTest(unittest.TestCase):
    def test_reasoning_is_subset_and_empty_response_cost_is_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generation = root / 'rvprobe/round-1/generation'
            attempt = generation / 'attempt-1'
            attempt.mkdir(parents=True)
            rows = [
                {'id': 'bootstrap', 'phase': 'model-request', 'status': 'ok', 'attempt': 'attempt-1',
                 'tool_step': 0, 'usage': {'prompt_tokens': 10, 'completion_tokens': 2, 'total_tokens': 12},
                 'usage_details': {'reasoning_tokens': 0}, 'output_characters': 0},
                {'id': 'generate', 'phase': 'model-request', 'status': 'ok', 'attempt': 'attempt-1',
                 'tool_step': 1, 'usage': {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150},
                 'usage_details': {'reasoning_tokens': 50}, 'output_characters': 0},
            ]
            events = generation / 'events.jsonl'
            events.write_text('\n'.join(json.dumps(row) for row in rows) + '\n')
            (attempt / 'prompt.json').write_text(json.dumps({'characters': 20, 'sections': []}))
            (generation.parent / 'feedback.json').write_text(json.dumps({'gaps': [], 'shared_context': {'initial_sequences': ['a']}}))
            before = {p: p.read_bytes() for p in root.rglob('*') if p.is_file()}
            result = analyze(root)
            self.assertEqual(result['partition'], {'input_tokens': 110, 'reasoning_tokens': 50,
                                                   'non_reasoning_completion_tokens': 2})
            self.assertEqual(result['rvprobe_costs']['usage_reported']['total_tokens'], 162)
            self.assertEqual(len(result['empty_generation_responses']), 1)
            self.assertEqual(before, {p: p.read_bytes() for p in root.rglob('*') if p.is_file()})
            del rows[1]['usage_details']['reasoning_tokens']
            events.write_text('\n'.join(json.dumps(row) for row in rows) + '\n')
            unknown = analyze(root)['partition']
            self.assertIsNone(unknown['reasoning_tokens'])
            self.assertIsNone(unknown['non_reasoning_completion_tokens'])


if __name__ == '__main__':
    unittest.main()
