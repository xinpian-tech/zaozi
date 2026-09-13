import unittest
from pathlib import Path
from types import SimpleNamespace
from event_trace import clock_commands, validate_clocks, event_frames, idle_events
from sequence_framework import Port

FIXTURE = Path(__file__).parent / 'tests/fixtures/multiclock_event.vcd.txt'
CLOCKS = [{'port':'clock','period_ps':10000}, {'port':'c2','period_ps':6000}]


def design():
    ports = [Port('clock','input',1,'clock'), Port('reset','input',1),
             Port('c2','input',1,'clock'), Port('x','input',1),
             Port('a','output',8), Port('b','output',8)]
    return SimpleNamespace(clock='clock', reset='reset', reset_active_low=False,
                           ports=ports, data_ports=ports[2:])


class ClockScheduleTests(unittest.TestCase):
    def test_rational_frequencies_preserve_ratio(self):
        clocks = [{'port':'wb_clk_i','period_ps':10000}, {'port':'core_clk_i','period_ps':6000}]
        self.assertEqual(validate_clocks(clocks), 2000)
        self.assertEqual(clock_commands(clocks, 'wb_clk_i'),
                         ['clock clock -factor 5', 'clock core_clk_i -factor 3', 'clock -rate -default clock'])

    def test_bad_clock_schedules_rejected(self):
        for rows in ([], [{'port':'a; exit','period_ps':10000}], [{'port':'a','period_ps':3}],
                     [{'port':'a','period_ps':10}, {'port':'a','period_ps':6}]):
            with self.assertRaises(ValueError): validate_clocks(rows)

    def test_real_jg_witness_keeps_secondary_edges_and_final_cover_edge(self):
        rows = event_frames(FIXTURE, design(), CLOCKS)
        self.assertEqual(rows[0]['clocks'], {'clock':1,'c2':1})
        self.assertEqual(rows[-1]['clocks'], {'clock':1,'c2':1})
        self.assertEqual(rows[-1]['expected']['a'], [5,255])
        self.assertEqual(rows[-1]['expected']['b'], [5,255])
        self.assertEqual(sum(r['duration_ps'] for r in rows[:-1]), 30000)
        self.assertTrue(any(not r['formal_sample'] for r in rows))
        self.assertTrue(all(set(r['drive']) == {'x'} for r in rows))

    def test_wrong_clock_ratio_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'waveform'):
            event_frames(FIXTURE, design(), [{'port':'clock','period_ps':10000},
                                             {'port':'c2','period_ps':10000}])

    def test_reset_prefix_ends_on_common_phase(self):
        config = {'idle':{'x':0}, 'environment':{'clocks':CLOCKS}}
        rows = idle_events(design(), config, 4, 'reset')
        self.assertEqual(sum(r['duration_ps'] for r in rows), 60000)
        self.assertEqual(rows[-1]['clocks'], {'clock':0,'c2':0})

    def test_clock_roles_must_match_schedule(self):
        from environment_contract import VERSION, validate_environment
        env = {'version':VERSION,'clocks':CLOCKS}
        validate_environment(design(), env)
        with self.assertRaisesRegex(ValueError, 'clock roles'):
            validate_environment(design(), {**env,'clocks':CLOCKS[:1]})

    def test_prepared_job_cannot_change_clock_reset_or_assumptions(self):
        from copy import deepcopy
        from environment_contract import VERSION, reset_sequence, formal_assumptions, verify_solver_environment
        env = {'version':VERSION,'clocks':CLOCKS,'static':{'x':1}}
        config = {'environment':env,'idle':{'x':0},'reset_cycles':4}
        job = {'clocks':[{'port':'clock','factor':5},{'port':'c2','factor':3}],
               'resetSequence':reset_sequence(design(),config),
               'environmentAssumptions':formal_assumptions(design(),env)}
        self.assertIn("x 1'h1",job['resetSequence'])
        verify_solver_environment(job,design(),config)
        for key,value in [('clocks',[]),('resetSequence',None),('environmentAssumptions',[])]:
            changed = deepcopy(job)
            changed[key] = value
            with self.assertRaisesRegex(ValueError,'differ'):
                verify_solver_environment(changed,design(),config)

    def test_feedback_masks_only_output_enabled_bits(self):
        from environment_contract import VERSION, validate_environment, formal_assumptions
        probe = design()
        probe.ports += [Port('pins','input',8)]
        env = {'version':VERSION,'clocks':CLOCKS,
               'feedback':[{'input':'pins','output':'a','enable':'b'}]}
        validate_environment(probe,env)
        self.assertEqual(formal_assumptions(probe,env),['reset || (pins & b) == (a & b)'])

    def test_unvalidated_event_bundle_is_rejected(self):
        from environment_contract import VERSION, verify_shared_environment
        with self.assertRaisesRegex(ValueError,'elaborated RTL evidence'):
            verify_shared_environment(design(),{'version':VERSION,'clocks':CLOCKS},{})

    def test_passive_pad_constraint_is_derived_from_ownership(self):
        from environment_contract import VERSION, passive_open_drain, formal_assumptions, validate_environment
        probe = design()
        probe.ports += [Port('pad','input',1), Port('out','output',1), Port('oe_n','output',1)]
        pad = {'input':'pad','output':'out','enable_n':'oe_n'}
        bp = {'open_drain_connections':[pad], 'environment_contract':{
            'infrastructure_owned':{'pad':'environment:pad'}}}
        self.assertEqual(passive_open_drain(bp), ['pad'])
        env = {'version':VERSION,'clocks':CLOCKS,'open_drain':[pad], 'passive_open_drain':['pad']}
        self.assertEqual(formal_assumptions(probe,env), ['reset || pad == (oe_n | out)'])
        bp['environment_contract']['bfm_owned'] = {'pad':'bfm:slave'}
        self.assertEqual(passive_open_drain(bp), [])
        del env['passive_open_drain']
        self.assertEqual(formal_assumptions(probe,env), ['reset || !pad || oe_n || out'])
        for names in [['missing'], ['pad','pad'], 'pad']:
            with self.assertRaisesRegex(ValueError,'passive open-drain'):
                validate_environment(probe,{**env,'passive_open_drain':names})
