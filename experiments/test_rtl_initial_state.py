import json
import os
from pathlib import Path
import tempfile
import unittest
import re
import shlex
import subprocess
from types import SimpleNamespace

from rtl_initial_state import (state_ports, render_probe, parse_state, validate_prior, generate,
                              verify_prepared, initialized_ports, UnsupportedNativeReset,
                              prepare_native_reset, verify_native_reset)
from unittest.mock import patch
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

    def test_power_on_keeps_unknown_bits_and_rejects_missing_values(self):
        ports = [{'name':'dut.state', 'width':4}]
        self.assertEqual(parse_state('RVPROBE_INIT dut.state 01x1\nRVPROBE_INIT_DONE', ports,
                                    allow_unknown=True), {'dut.state':"4'b01x1"})
        with self.assertRaises(ValueError): parse_state('RVPROBE_INIT_DONE', ports, allow_unknown=True)

    def test_only_constant_initial_storage_is_selected(self):
        tree, design = self.fixture()
        ports = state_ports(tree, design)
        initial = {'type':'INITIALSTATIC', 'stmtsp':[{'type':'ASSIGN',
            'rhsp':[{'type':'CONST', 'name':"13'h5"}],
            'lhsp':[{'type':'VARREF', 'varp':'v', 'access':'WR'}]}]}
        statements = tree['modulesp'][0]['stmtsp']
        statements.append(initial)
        self.assertEqual(initialized_ports(tree, design, ports), ports)
        statements.append(initial)
        with self.assertRaisesRegex(UnsupportedNativeReset, 'multiple'): initialized_ports(tree, design, ports)
        statements.pop()
        for expression in ({'type':'RANDOM'}, {'type':'DELAY'}, {'type':'VARREF','varp':'input','access':'RD'}):
            initial['stmtsp'][0]['rhsp'] = [expression]
            with self.assertRaises(UnsupportedNativeReset): initialized_ports(tree, design, ports)

    def test_power_on_unsupported_is_cached_but_tool_failures_are_not_hidden(self):
        design = SimpleNamespace(record=lambda:{'top':'test'}, clock='clk')
        replay = {'idle':{}, 'reset_cycles':2}
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)/'state'
            with patch('rtl_initial_state.generate', side_effect=UnsupportedNativeReset('hierarchy')) as gen:
                self.assertEqual(prepare_native_reset(design, replay, out, Path('/unused')), (None,None))
                self.assertEqual(prepare_native_reset(design, replay, out, Path('/unused')), (None,None))
                self.assertEqual(gen.call_count, 1)
            with self.assertRaisesRegex(ValueError, 'inputs changed'):
                prepare_native_reset(design, {**replay,'reset_cycles':3}, out, Path('/unused'))
            with patch('rtl_initial_state.generate', side_effect=RuntimeError('license unavailable')):
                with self.assertRaisesRegex(RuntimeError, 'license'):
                    prepare_native_reset(design, replay, Path(directory)/'other', Path('/unused'))

    def test_power_on_provenance_is_required(self):
        verify_native_reset({}, None, {})
        with self.assertRaisesRegex(ValueError, 'provenance'):
            verify_native_reset({'resetSnapshotState':"dut.mem[0] 32'h0"}, None, {})

    def test_native_reset_provenance_detects_state_and_artifact_mutation(self):
        import rtl_initial_state as module
        from cycle_replay import digest
        design=SimpleNamespace(record=lambda:{'top':'test'},clock='clk')
        replay={'idle':{},'reset_cycles':2}
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);state="dut.state\n4'h5\n"
            (root/'initial.state').write_text(state)
            saved=dict(policy=module.NATIVE_RESET_POLICY,design=design.record(),idle={},reset_cycles=2,
                implementation=digest(Path(module.__file__)),
                artifact_sha256={str(root/'initial.state'):digest(root/'initial.state')})
            path=root/'snapshot.json';path.write_text(json.dumps(saved))
            job=dict(resetSnapshotState=state,resetSnapshotRecord=dict(policy=module.NATIVE_RESET_POLICY,
                file=str(path),sha256=digest(path)))
            verify_native_reset(job,design,replay)
            with self.assertRaises(ValueError): verify_native_reset({**job,'resetSnapshotState':state+'\n'},design,replay)
            with self.assertRaises(ValueError): verify_native_reset(job,design,{**replay,'reset_cycles':3})
            (root/'initial.state').write_text("dut.state\n4'h0\n")
            with self.assertRaises(ValueError): verify_native_reset(job,design,replay)

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

    def test_power_on_nonzero_initial_values_are_not_post_reset_values(self):
        design=Design('initial_state_fixture',(ROOT/'experiments/tests/fixtures/initial_state.v',),(),
            (Port('clk','input',1,'clock'),Port('rst','input',1,'bool'),Port('enable','input',1,'bool'),
             Port('address','input',2),Port('payload','input',13),Port('data','output',13),
             Port('counter','output',13),Port('flag','output',1,'bool')),
            'clk','rst',False,'unused_seq','unused_item','',(('WIDTH',13),))
        replay={'reset_cycles':2,'idle':{'enable':0,'address':0,'payload':0}}
        with tempfile.TemporaryDirectory(prefix='poweron-regression-',dir='/dev/shm') as temp:
            state=generate(design,replay,Path(temp)/'snapshot','',ROOT/'experiments/eda-shell',power_on=True)
            self.assertIn("dut.counter\n13'h5\n",state)
            self.assertIn("dut.flag\n1'h1\n",state)
            self.assertNotIn('dut.i\n',state)
            for i in range(4): self.assertIn(f"dut.memory[{i}]\n13'h{i+9:x}\n",state)
            # Native cumulative reset must advance the counter, clear the flag,
            # and preserve nonzero RAM; merely loading a snapshot is insufficient.
            from rvprobe.backend.process import run
            out=Path(temp)
            native=generate(design,replay,out/'native-snapshot','',ROOT/'experiments/eda-shell',native_reset=True)
            self.assertIn("dut.counter\n13'h7\n",native)
            self.assertIn("dut.flag\n1'h0\n",native)
            (out/'initial.state').write_text(native.replace('dut.', ''))
            (out/'reset.seq').write_text("rst 1'b1\nenable 1'b0\naddress 2'b0\npayload 13'b0\n2\nrst 1'b0\n$\n")
            (out/'check.tcl').write_text(
                f'clear -all\nanalyze -sv12 {{{design.sources[0]}}}\n'
                'elaborate -top initial_state_fixture -parameter WIDTH 13\nclock clk\n'
                f'set_cumulative_reset on\nreset -init_state {{{out}/initial.state}}\n'
                f'reset -sequence {{{out}/reset.seq}}\n'
                f'get_reset_info -save_values {{{out}/jg.state}} -all\nexit\n')
            result=run([str(ROOT/'experiments/eda-shell'), '-c', shlex.join([
                'jg','-batch','-tcl',str(out/'check.tcl'),'-proj',str(out/'jgproj')])],
                stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=120)
            self.assertEqual(result.returncode,0,result.stdout[-2000:])
            words=(out/'jg.state').read_text().split()
            observed=dict(zip(words[::2],words[1::2]))
            expected={'counter':7,'flag':0,'data':165,**{f'memory[{i}]':i+9 for i in range(4)}}
            for name,value in expected.items():
                literal=re.fullmatch(r"\d+'([bh])([0-9a-fA-F]+)",observed[name])
                self.assertIsNotNone(literal,observed[name])
                self.assertEqual(int(literal[2],2 if literal[1]=='b' else 16),value,name)
