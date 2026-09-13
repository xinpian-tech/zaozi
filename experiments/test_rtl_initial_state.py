import json
import os
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace

from rtl_initial_state import state_ports, render_probe, parse_state, validate_prior, generate, verify_prepared
from sequence_framework import ROOT, Design, Port
from cycle_replay import load_config


class InitialStateTest(unittest.TestCase):
    def fixture(self):
        variable={"type":"VAR", "addr":"v", "dtypep":"t", "verilogName":"state"}
        block={"type":"ALWAYS", "keyword":"always", "sentreep":[{"type":"SENITEM", "edgeType":"POS",
                "sensp":[{"type":"VARREF", "name":"clk"}]}],
               "stmtsp":[{"type":"VARREF", "access":"WR", "varp":"v"}]}
        tree={"type":"NETLIST", "modulesp":[{"type":"MODULE", "level":1,"origName":"test",
                                               "stmtsp":[variable,block]}],
              "miscsp":[{"type":"BASICDTYPE", "addr":"t", "keyword":"logic", "range":"12:0"}]}
        return tree,SimpleNamespace(top="test",clock="clk",reset="rst",reset_active_low=False)

    def test_discovers_width_without_inspecting_design_names_or_answers(self):
        tree,design=self.fixture()
        self.assertEqual(state_ports(tree,design),[{"name":"dut.state","width":13}])

    def test_expands_array_with_nonzero_bounds(self):
        tree,design=self.fixture()
        tree['modulesp'][0]['stmtsp'][0]['dtypep']='a'
        tree['miscsp'].append({'type':'UNPACKARRAYDTYPE','addr':'a','declRange':'[5:3]','refDTypep':'t'})
        self.assertEqual(state_ports(tree,design),[{'name':f'dut.state[{i}]','width':13} for i in (3,4,5)])

    def test_rejects_hierarchy_other_clocks_and_latches(self):
        for mode in ('hierarchy','negedge','other-clock','latch'):
            tree,design=self.fixture()
            statements=tree['modulesp'][0]['stmtsp']
            if mode=='hierarchy': statements.append({'type':'CELL'})
            if mode=='negedge': statements[1]['sentreep'][0]['edgeType']='NEG'
            if mode=='other-clock': statements[1]['sentreep'][0]['sensp'][0]['name']='other'
            if mode=='latch': statements[1]['keyword']='always_latch'
            with self.subTest(mode=mode),self.assertRaises(ValueError): state_ports(tree,design)

    def test_unknown_missing_duplicate_and_wrong_width_are_not_zero_filled(self):
        ports=[{'name':'dut.state','width':4}]
        for log in ('RVPROBE_INIT dut.state 00x1\nRVPROBE_INIT_DONE',
                    'RVPROBE_INIT_DONE', 'RVPROBE_INIT dut.state 1\nRVPROBE_INIT_DONE',
                    'RVPROBE_INIT dut.state 0001\nRVPROBE_INIT dut.state 0001\nRVPROBE_INIT_DONE'):
            with self.subTest(log=log),self.assertRaises(ValueError): parse_state(log,ports)
        self.assertEqual(parse_state('RVPROBE_INIT dut.state 1010\nRVPROBE_INIT_DONE',ports),{'dut.state':"4'ha"})

    def test_prior_must_agree_but_can_be_incomplete(self):
        values={'dut.mem[0]':"8'h0",'dut.state':"13'h123"}
        validate_prior("dut.mem[0]\n8'h0\n",values)
        for prior in ("dut.mem[0] 8'h1", "dut.missing 8'h0", "dut.state 8'h23", "dut.mem[0] 8'h0 dut.mem[0] 8'h0"):
            with self.subTest(prior=prior),self.assertRaises(ValueError):validate_prior(prior,values)

    def test_old_partial_solver_job_cannot_be_resampled(self):
        with self.assertRaisesRegex(ValueError,'regenerate'):
            verify_prepared({'initialState':"dut.mem[0] 8'h0"},None,{'formal_initial_state':{'file':'old'}})

    def test_replay_accepts_automatic_mode_without_handwritten_state(self):
        raw=json.loads((ROOT/'experiments/tests/fixtures/tiny_replay.json').read_text())
        raw['design']=str(ROOT/'experiments/tests/fixtures/tiny_design.json')
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'replay.json'
            for policy in ({'mode':'rtl-reset-simulation'},None,{}, {'mode':'guess'}, {'file':'x','sha256':'bad'}):
                raw['formal_initial_state']=policy
                path.write_text(json.dumps(raw))
                if policy=={'mode':'rtl-reset-simulation'}:
                    self.assertEqual(load_config(path)[1]['formal_initial_state'],policy)
                else:
                    with self.subTest(policy=policy),self.assertRaisesRegex(ValueError,'initial state policy'):
                        load_config(path)


@unittest.skipUnless(os.environ.get('RVPROBE_RUN_SNAPSHOT_TESTS')=='1','requires pinned Verilator and licensed VCS')
class InitialStateToolTest(unittest.TestCase):
    def test_original_rtl_initial_blocks_nonzero_values_and_reset_are_simulated(self):
        design=Design('initial_state_fixture',(ROOT/'experiments/tests/fixtures/initial_state.v',),(),
            (Port('clk','input',1,'clock'),Port('rst','input',1,'bool'),Port('enable','input',1,'bool'),
             Port('address','input',2),Port('payload','input',13),Port('data','output',13),
             Port('counter','output',13),Port('flag','output',1,'bool')),
            'clk','rst',False,'unused_seq','unused_item','',(('WIDTH',13),))
        replay={'reset_cycles':2,'idle':{'enable':0,'address':0,'payload':0}}
        with tempfile.TemporaryDirectory(prefix='snapshot-regression-',dir=ROOT/'out/experiments') as temp:
            output=Path(temp)/'snapshot'
            state=generate(design,replay,output,'',ROOT/'experiments/eda-shell')
            self.assertIn("dut.data\n13'ha5\n",state)
            self.assertIn("dut.counter\n13'h7\n",state)
            self.assertIn("dut.flag\n1'h0\n",state)
            for i in range(4): self.assertIn(f"dut.memory[{i}]\n13'h{i+9:x}\n",state)
            self.assertEqual(state,generate(design,replay,output,'',ROOT/'experiments/eda-shell'))
            (output/'initial.state').write_text(state+'\n')
            with self.assertRaisesRegex(ValueError,'artifacts changed'):
                generate(design,replay,output,'',ROOT/'experiments/eda-shell')
