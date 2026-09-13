import json
from pathlib import Path
import unittest
from coverage_table import pack,unpack
from task_context import TaskContext
from sequence_framework import load_design
from sequence_framework import render_model_ut, parse_response


class CoverageTableTests(unittest.TestCase):
    def test_exact_roundtrip_including_missing_null_nested_and_duplicate_rows(self):
        rows=[{'type':'toggle','signal':'a','hit':0},{'type':'toggle','signal':'b','hit':0},
              {'type':'toggle','signal':'b','hit':0},{'nested':[True],'x':None},
              {'nested':[1],'x':None},{'nested':[1.0]},'raw',None,{}]
        self.assertEqual(json.dumps(unpack(pack(rows)),sort_keys=True),json.dumps(rows,sort_keys=True))
        self.assertEqual(unpack(pack([])),[])

    def test_all_pages_reconstruct_original_order_without_filters(self):
        design=load_design(Path(__file__).parent/'tests/fixtures/tiny_design.json')
        gaps=[{'type':'toggle','signal':f'signal_{i}','direction':'0->1','context':'x'*100} for i in range(4097)]
        context=TaskContext(design,{'gaps':gaps,'bins':{'toggle':[1058,529]}})
        offset=0;decoded=[]
        while offset is not None:
            page=context.dispatch('read_coverage',{'offset':offset})
            decoded.extend(unpack(page['gaps']));offset=page['next_offset']
            self.assertEqual(page['metadata']['bins'],{'toggle':[1058,529]})
        self.assertEqual(decoded,gaps)
        self.assertLess(len(json.dumps(pack(gaps))),len(json.dumps(gaps)))

    def test_invalid_offsets_and_oversized_rows_have_verbose_fallback(self):
        design=load_design(Path(__file__).parent/'tests/fixtures/tiny_design.json')
        context=TaskContext(design,{'gaps':[{'context':'x'*25000}]})
        for args in ({'offset':True},{'offset':2},{'limit':0},{'limit':2049}):
            with self.assertRaises(ValueError):context.dispatch('read_coverage',args)
        with self.assertRaisesRegex(ValueError,'read_context'):context.dispatch('read_coverage',{})
        self.assertIn('x'*12000,context.topics['coverage'])

    def test_wiring_shell_has_no_goals_or_operands_and_preserves_every_connection(self):
        design=load_design(Path(__file__).parent/'tests/fixtures/tiny_design.json');source=render_model_ut(design, parse_response('Gen(io.valid, "goal")'))
        self.assertEqual(source.count('Gen('),1);self.assertNotIn('Assume(',source)
        for p in design.data_ports:
            connection=(f'dut.io.`{p.name}` := io.`{p.name}`' if p.direction=='input' else f'io.`{p.name}` := dut.io.`{p.name}`')
            self.assertEqual(source.count(connection),1)
        self.assertIn('dut.io.`clk` := io.clock',source)


if __name__=='__main__':unittest.main()
