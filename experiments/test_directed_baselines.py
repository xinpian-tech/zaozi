import backend_imports
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest

from directed_baselines import parse_response, stimulus_rows, sva_wrapper, ModelOutputError, direct_dialogue
from directed_experiment import coverage_loop
from sequence_framework import Port
from run_records import totals


def fixture():
    ports=[Port('clk','input',1,'clock'),Port('rst_n','input',1,'bool'),
           Port('slow','input',1,'clock'),Port('data','input',8,'bits'),
           Port('req','input',1,'bool'),Port('cfg','input',1,'bool'),Port('ack','output',1,'bool')]
    design=SimpleNamespace(top='example',clock='clk',reset='rst_n',reset_active_low=True,
        ports=ports,data_ports=ports[2:],parameters=())
    config={'reset_cycles':2,'idle':{'data':0,'req':0,'cfg':1},'environment':{
        'version':'shared-event-environment-v1','clocks':[{'port':'clk','period_ps':10},{'port':'slow','period_ps':6}],
        'static':{'cfg':1},'extra_resets':[],'boundary':'independent-dut-v1'}}
    return design,config


class DirectContracts(unittest.TestCase):
    def test_unsigned_drives_and_multi_clock_edges(self):
        design,config=fixture()
        rows=stimulus_rows({'steps':[{'cycles':1,'drive':{'data':'0xff','req':1}},
                                      {'cycles':2,'drive':{'req':0}}]},design,config,7)
        reset=[r for r in rows if r['kind']=='reset']
        witness=[r for r in rows if r['kind']=='witness']
        self.assertEqual(sum(r['duration_ps'] for r in reset),30)  # full clock superperiod
        self.assertEqual(sum(r['duration_ps'] for r in witness),30)
        at=0
        for row in witness:
            self.assertEqual(row['drive'],{'data':255,'req':int(at<10),'cfg':1})
            self.assertEqual(row['clocks'],{'clk':int(at%10<5),'slow':int(at%6<3)})
            at+=row['duration_ps']
        self.assertTrue(all(r['segment']==7 and r['expected']=={} for r in rows))

    def test_invalid_or_owned_pins_fail_without_wrapping(self):
        design,config=fixture()
        for drive in ({'data':-1},{'data':256},{'data':True},{'ack':1},{'clk':1},
                      {'rst_n':0},{'cfg':0},{'data':'x'},{'data':'$(bad)'}):
            with self.subTest(drive=drive),self.assertRaises(ModelOutputError):
                stimulus_rows({'steps':[{'cycles':1,'drive':drive}]},design,config,0)

    def test_sva_dut_wiring_and_original_temporal_expression(self):
        design,_=fixture()
        _,source=sva_wrapper(design,[{'label':'test','property':"@(posedge clk) req ##1 (ack && data == 8'hff)"}])
        self.assertIn('.rst_n(~reset)',source)
        self.assertIn("test: cover property (@(posedge clock) req ##1 (ack && data == 8'hff));",source)
        _,source=sva_wrapper(design,[{'label':'test','property':"@(posedge clk) req |=> ack"}])
        self.assertIn('|=>',source)

    def test_sva_rejects_host_access_assumptions_and_mutation(self):
        design,_=fixture()
        for expression in ('1); assume property (1', '$system(1)', 'dut.internal',
                           'req = 1', '`include secret', 'req; endmodule', '"secret"'):
            with self.subTest(expression=expression),self.assertRaises(ModelOutputError):
                sva_wrapper(design,[{'label':'test','property':expression}])

    def test_empty_or_duplicate_intents_are_not_success(self):
        self.assertIsNone(parse_response('STOP','directed_sva'))
        item={'label':'a','intent':'test','property':'1'}
        for value in ({'intents':[]},{'intents':[item,item]},{'intents':[{'label':'a'}]}):
            with self.assertRaises(ModelOutputError):parse_response(json.dumps(value),'directed_sva')

    def test_dialogue_no_ltl_instructions_or_unobserved_assistant_history(self):
        class Context:
            tools=[]
            def record(self):return {}
            def initial_evidence(self):return {'coverage':{}}
        seen=[]
        def send(payload,timeout):
            seen.append(payload)
            return {'model':'test','choices':[{'finish_reason':'stop','message':{'content':'STOP'}}],
                    'usage':{'total_tokens':10,'prompt_tokens':8,'completion_tokens':2}}
        with TemporaryDirectory() as tmp:
            directory=Path(tmp)/'attempt'
            raw=direct_dialogue('Output stimulus JSON',Context(),SimpleNamespace(model='test',temperature=.3,timeout=600),directory,send)
            self.assertEqual(raw,'STOP')
            self.assertEqual(totals(directory)['usage_reported']['total_tokens'],10)
        self.assertNotIn('LTL',json.dumps(seen))
        self.assertTrue(all(m['role']=='user' for m in seen[0]['messages']))

    def test_loop_keeps_last_accepted_coverage_when_generator_fails(self):
        backend=SimpleNamespace(method='directed_sva',history=[])
        backend.generate=lambda *args:(_ for _ in ()).throw(RuntimeError('provider timeout'))
        baseline={'score':20,'percent':{'line':20},'bins':{'line':[10,2]},'modules':['example'],'uncovered':[]}
        with TemporaryDirectory() as tmp:
            result=coverage_loop({'sequences':[]},Path(tmp),lambda *a:baseline,backend)
        self.assertEqual(result['status'],'failed')
        self.assertEqual(result['final']['score'],20)
        self.assertEqual(result['failed_round'],1)


if __name__=='__main__':unittest.main()
