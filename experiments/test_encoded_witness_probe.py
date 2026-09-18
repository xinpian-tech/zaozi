import backend_imports
import unittest
from encoded_witness_probe import diagnostic_config
from rvprobe.backend.encoding import leaves, terminal_result, trace_result, resampling_commands, preference_ports, distinct_input_constraint
from types import SimpleNamespace
from pathlib import Path
from rvprobe.backend.initialization import materialize_initializers, global_ff_commands, expose_collision_guards, preserve_single_driver_masks, canonicalize_output_aliases, remap_encoded_expression, audit_encoded_goal_rails, BOOT, GLOBAL_CLOCK
from rvprobe.backend.initialization import pack_output_bits, bind_packed_expression


class ExplicitInitialization(unittest.TestCase):
    def test_overlapping_output_bits_export_once_and_keep_initializers(self):
        ports = {name: {'direction': 'output', 'bits': bits} for name, bits in
                 [('bus', [3, 4, 5]), ('atom', [3]), ('slice', [4, 5]),
                  ('reversed', [5, 3]), ('repeated', [3, 3, 'x', 'x', '0'])]}
        ports['input'] = {'direction': 'input', 'bits': [6]}
        nets = {'bus': {'bits': [3, 4, 5], 'attributes': {'init': '1x0'}}}
        model = {'modules': {'top': {'ports': ports, 'netnames': nets, 'cells': {}}}}
        mapped, bindings = pack_output_bits(model, 'top')
        self.assertEqual(mapped['modules']['top']['ports']['rvp_encoded_outputs']['bits'],
                         [3, 4, 5, 'x', '0'])
        self.assertEqual(mapped['modules']['top']['netnames']['bus'], nets['bus'])
        self.assertEqual(len(ports), 6)  # source untouched
        self.assertEqual(bindings['repeated'], [0, 0, 3, 3, 4])
        self.assertEqual(bind_packed_expression('atom_d && !atom_x', bindings),
                         'rvp_encoded_outputs_d[0] && !rvp_encoded_outputs_x[0]')
        self.assertEqual(bind_packed_expression('reversed_d', bindings),
                         '{rvp_encoded_outputs_d[0], rvp_encoded_outputs_d[2]}')
        with self.assertRaisesRegex(ValueError, 'unmapped'):
            bind_packed_expression('missing_d', bindings)
        with self.assertRaisesRegex(ValueError, 'collision'):
            pack_output_bits(mapped, 'top')

    def test_combinational_x_cannot_reenter_a_claimed_known_atom(self):
        model = {'modules':{'top':{'ports':{'p_d':{'direction':'output','bits':[2]},
            'p_x':{'direction':'output','bits':['0']}, 'data':{'direction':'input','bits':[3]}},
            'cells':{'mux':{'type':'$mux', 'port_directions':{'A':'input','B':'input','S':'input','Y':'output'},
                           'connections':{'A':['x'],'B':[3],'S':['0'],'Y':[2]}}}}}}
        with self.assertRaisesRegex(ValueError,'unencoded X/Z'):
            audit_encoded_goal_rails(model,'top',['p_d && !p_x'])
        model['modules']['top']['cells']['mux']['connections']['A']=['0']
        self.assertEqual(audit_encoded_goal_rails(model,'top',['p_d && !p_x'])['rails'],['p_d','p_x'])

    def test_structural_output_aliases_share_both_rails_without_mutating_source(self):
        ports = {name: {'direction':'output','bits':bits} for name,bits in
                 [('data',[3]),('rvp_encoded_atom_0',[3]),('rvp_encoded_atom_1',[3]),('other',[4])]}
        model = {'modules':{'top':{'ports':ports,'netnames':{n:{'bits':p['bits']} for n,p in ports.items()}}}}
        mapped, aliases = canonicalize_output_aliases(model, 'top')
        self.assertEqual(aliases, {'rvp_encoded_atom_1':'rvp_encoded_atom_0', 'data':'rvp_encoded_atom_0'})
        self.assertEqual(set(mapped['modules']['top']['ports']), {'rvp_encoded_atom_0','other'})
        self.assertEqual(len(model['modules']['top']['ports']), 4)
        self.assertEqual(remap_encoded_expression('rvp_encoded_atom_1_d && !rvp_encoded_atom_1_x', aliases),
                         'rvp_encoded_atom_0_d && !rvp_encoded_atom_0_x')
        model['modules']['top']['netnames']['data']['attributes'] = {'init':'1'}
        mapped, _ = canonicalize_output_aliases(model, 'top')
        self.assertEqual(mapped['modules']['top']['netnames']['rvp_encoded_atom_0']['attributes']['init'],'1')
        model['modules']['top']['netnames']['rvp_encoded_atom_1']['attributes'] = {'init':'0'}
        with self.assertRaisesRegex(ValueError, 'conflicting'):
            canonicalize_output_aliases(model, 'top')

    def test_lone_driver_enable_is_not_erased(self):
        cell=lambda y:{'type':'$_TBUF_','connections':{'A':[2],'E':[3],'Y':[y]}}
        model={'modules':{'top':{'cells':{'one':cell(4),'overlap_a':cell(5),'overlap_b':cell(5)}}}}
        mapped,names=preserve_single_driver_masks(model,'top')
        self.assertEqual(names,['one'])
        self.assertEqual(mapped['modules']['top']['cells']['one']['connections'],
            {'A':['x'],'B':[2],'S':[3],'Y':[4]})
        self.assertEqual(mapped['modules']['top']['cells']['overlap_a']['type'],'$_TBUF_')
        self.assertEqual(model['modules']['top']['cells']['one']['type'],'$_TBUF_')

    def test_only_generated_collision_guards_are_exported(self):
        model = {'modules': {'top': {'ports': {}, 'netnames': {}, 'cells': {
            '$tribuf_conflict$x': {'type': '$assume', 'connections': {'EN': ['1'], 'A': [9]}}}}}}
        mapped, guards = expose_collision_guards(model, 'top')
        self.assertEqual(guards, ['rvp_encoded_guard_0'])
        self.assertEqual(mapped['modules']['top']['ports'][guards[0]]['bits'], [9])
        self.assertEqual(mapped['modules']['top']['cells'], {})
        self.assertTrue(model['modules']['top']['cells'])
        model['modules']['top']['cells']['rtl_assumption'] = {'type': '$assume', 'connections': {'EN': ['1'], 'A': [2]}}
        with self.assertRaisesRegex(ValueError, 'non-collision'):
            expose_collision_guards(model, 'top')

    def test_global_ffs_are_clocked_without_blackboxing_or_overwriting_user_cells(self):
        model = {'modules': {'top': {'netnames': {}, 'cells': {'$auto$ff': {'type': '$ff'}, 'ordinary': {'type': '$dff'}}}}}
        commands, names, mapped = global_ff_commands(model, 'top')
        self.assertEqual(names, ['rvp_global_ff_0'])
        tick = mapped['modules']['top']['ports'][GLOBAL_CLOCK]['bits']
        self.assertEqual(mapped['modules']['top']['cells']['rvp_global_ff_0']['connections']['CLK'], tick)
        self.assertEqual(mapped['modules']['top']['cells']['rvp_global_ff_0']['type'], '$dff')
        self.assertEqual(model['modules']['top']['cells']['$auto$ff']['type'], '$ff')
        self.assertIn('rvp_global_ff_0', mapped['modules']['top']['cells'])
        model['modules']['top']['cells']['rvp_global_ff_0'] = {'type': '$dff'}
        with self.assertRaisesRegex(ValueError, 'collision'):
            global_ff_commands(model, 'top')

    code = """module demo(clock, reset, we);
input clock, reset, we;
reg mask = 1'h1;
reg value;
reg [1:0] \\dut.data  = 2'h2;
always @(posedge clock)
  mask <= we ? 1'b0 : mask;
always @(posedge clock)
  value <= we;
always @(negedge clock)
  \\dut.data  <= 2'b0;
endmodule
"""

    def test_only_initialized_state_is_loaded_and_phase_is_preserved(self):
        seq = "reset 1'b1\nwe 1'b0\n60\nreset 1'b0\n$\n"
        code, reset, info = materialize_initializers(
            self.code, seq, [{'factor': 3}, {'factor': 5}])
        self.assertEqual(info['boot_cycles'], 30)
        self.assertEqual(info['initialized_registers'], {'mask': "1'h1", '\\dut.data': "2'h2"})
        self.assertIn(f'if ({BOOT}) mask <= 1\'h1;', code)
        self.assertIn('value <= we;', code)
        self.assertNotIn(f'if ({BOOT}) value', code)
        self.assertTrue(reset.endswith(seq))
        self.assertIn(f"30\n{BOOT} 1'b0\n", reset)

    def test_missing_or_unsupported_state_writer_rejected(self):
        for code in (self.code.replace('mask <=', 'other <='),
                     self.code.replace("1'h1", "1'bx"),
                     self.code.replace('mask <=', 'begin mask <=')):
            with self.assertRaises(ValueError):
                materialize_initializers(code, "reset 1'b1\n10\n$", [{'factor': 1}])

    def test_yosys_decimal_and_binary_initializers(self):
        code = self.code.replace("1'h1", "1'b1").replace("2'h2", "2'd2")
        transformed, _, _ = materialize_initializers(code, "reset 1'b1\n10\n$", [{'factor': 1}])
        self.assertIn(f"if ({BOOT}) mask <= 1'b1", transformed)
        self.assertIn(f"if ({BOOT}) \\dut.data <= 2'd2", transformed)
        with self.assertRaisesRegex(ValueError, 'width'):
            materialize_initializers(code.replace("2'd2", "2'd4"), "reset 1'b1\n10\n$", [{'factor': 1}])


class SequenceAtoms(unittest.TestCase):
    def test_resampling_shortfall_is_not_a_solver_crash_or_unreachable_goal(self):
        for status in ('undetermined', 'unreachable', 'error'):
            log = f'ENCODED_GOAL covered\nENCODED_TRACE_RESULT {status}\n'
            result = trace_result(log, resampling=True)
            self.assertEqual(result['status'], 'resampling_exhausted')
            self.assertEqual(result['solver_status'], 'covered')
            self.assertEqual(result['trace_status'], status)
            self.assertEqual(result['termination_reason'], 'trace_resampling_' + status)
            self.assertEqual(trace_result(log, resampling=True, diversity=True)['status'],
                             'no_distinct_candidate')
        commands = '\n'.join(resampling_commands('120s'))
        self.assertIn('ENCODED_TRACE_RESULT', commands)
        self.assertIn('{ exit }', commands)
        self.assertNotIn('error ', commands)

    def test_missing_trace_evidence_still_fails_closed(self):
        for log in ('ENCODED_GOAL covered\n',
                    'ENCODED_GOAL covered\nENCODED_TRACE_RESULT covered\nENCODED_TRACE_RESULT covered\n'):
            with self.assertRaises(ValueError): trace_result(log, resampling=True)
        with self.assertRaises(ValueError):
            trace_result('ENCODED_GOAL undetermined\nENCODED_TRACE_RESULT covered\n', resampling=True)
        self.assertEqual(trace_result('ENCODED_GOAL undetermined\n',resampling=True)['status'], 'undetermined')
        self.assertEqual(trace_result('ENCODED_GOAL covered\nENCODED_TRACE_RESULT covered\n',
                                     resampling=True)['status'], 'covered')

    def test_distinct_constraint_excludes_each_prior_trace_without_editing_it(self):
        ports=[SimpleNamespace(name='data',width=8)]
        traces=[[{'data':'5'}],[{'data':'7'}]]
        got=distinct_input_constraint(traces,ports,1,42)
        self.assertEqual(got['expression'],"data != 8'h5 && data != 8'h7")
        self.assertEqual(got['cycle'],1)
        self.assertEqual(traces,[[{'data':'5'}],[{'data':'7'}]])
        with self.assertRaises(ValueError):distinct_input_constraint(traces,ports,2,42)
        with self.assertRaises(ValueError):
            distinct_input_constraint([[{'b':'0'}],[{'b':'1'}]],[SimpleNamespace(name='b',width=1)],1,0)

    def test_soft_preferences_never_request_changes_to_fixed_inputs(self):
        port=lambda n,d='input',k='data':SimpleNamespace(name=n,direction=d,kind=k)
        design=SimpleNamespace(data_ports=[port('cfg'),port('data'),port('out','output'),port('clk',k='clock')])
        config={'environment':{'static':{'cfg':1}}}
        self.assertEqual([p.name for p in preference_ports(design,config)],['data'])
        self.assertEqual(config,{'environment':{'static':{'cfg':1}}})

    def test_inconsistent_environment_is_not_unreachable_intent(self):
        result = terminal_result('WARNING (WAS006): The task is inconsistent at cycle 1.\nENCODED_GOAL unreachable\n')
        self.assertEqual(result['status'], 'inconsistent_environment')
        self.assertEqual(result['solver_status'], 'unreachable')
        self.assertTrue(result['environment_conflict_detected'])
        self.assertEqual(terminal_result('ENCODED_GOAL covered\n')['status'], 'covered')
        self.assertEqual(terminal_result('ENCODED_GOAL unreachable\n')['status'], 'unreachable')
        for log in ('', 'ENCODED_GOAL covered\nENCODED_GOAL covered\n',
                    'overconstrained\nENCODED_GOAL covered\n'):
            with self.assertRaises(ValueError):
                terminal_result(log)

    def test_time_limit_is_reported_without_claiming_unreachability(self):
        result = terminal_result('INFO: The proof thread was terminated because of a time_limit expiration.\n'
                                 'ENCODED_GOAL undetermined\n')
        self.assertEqual(result['status'],'undetermined')
        self.assertEqual(result['termination_reason'],'solver_time_limit')
        self.assertEqual(terminal_result('ENCODED_GOAL undetermined\n')['termination_reason'], 'undetermined')
        self.assertEqual(terminal_result('time_limit\nENCODED_GOAL covered\n')['termination_reason'], 'covered')

    def test_independent_boundary_is_explicit_and_does_not_edit_frozen_config(self):
        config = {'design': 'design.json', 'reset_cycles': 10, 'environment': {
            'clocks': [{'port': 'clk', 'period_ps': 10}], 'static': {'cfg': 1},
            'extra_resets': [], 'open_drain': [{'input': 'pad'}], 'feedback': []}}
        result = diagnostic_config(config, Path('/frozen/replay.json'), 'independent-dut-v1')
        self.assertEqual(result['design'], '/frozen/design.json')
        self.assertEqual(result['environment']['boundary'], 'independent-dut-v1')
        self.assertEqual(result['environment']['open_drain'], [])
        self.assertEqual(result['environment']['static'], config['environment']['static'])
        self.assertEqual(config['environment']['open_drain'], [{'input': 'pad'}])
        self.assertNotIn('boundary', config['environment'])

    def test_boolean_expression_is_one_atom(self):
        atoms = []
        got = leaves('(a == 4\'hf && (!b || c))', atoms)
        self.assertEqual(atoms, ["a == 4'hf && (!b || c)"])
        self.assertEqual(got, '((rvp_encoded_atom_0_d && !rvp_encoded_atom_0_x))')

    def test_clocks_delays_and_boolean_negation_preserved(self):
        atoms = []
        got = leaves('(@(posedge clock) a & b) ##0 (@(posedge clock) ##[1:8] ~c ##[1:6] &d)', atoms)
        self.assertEqual(atoms, ['a & b', '~c', '&d'])
        for token in ('@(posedge clock)', '##0', '##[1:8]', '##[1:6]'):
            self.assertIn(token, got)
        self.assertIn('rvp_encoded_atom_2_d && !rvp_encoded_atom_2_x', got)

    def test_environment_uses_same_atom_encoding_without_reusing_names(self):
        atoms = []
        leaves('@(posedge clock) valid ##1 output_bit', atoms)
        env = leaves('pad_i == pad_release_o', atoms)
        self.assertEqual(atoms[-1], 'pad_i == pad_release_o')
        self.assertEqual(env, '(rvp_encoded_atom_2_d && !rvp_encoded_atom_2_x)')

    def test_unsupported_temporal_forms_fail_closed(self):
        for expression in ('$past(a)', 'a |-> b', 'a intersect b', 'a[*n]',
                           'a ##[1:$] b', '@(negedge clock) a', '(a ##1 b) && c'):
            with self.subTest(expression=expression):
                with self.assertRaises(ValueError):
                    leaves(expression, [])

    def test_repetition_keeps_native_empty_bounded_and_unbounded_semantics(self):
        for suffix in ('[*0]', '[*4]', '[*2:7]', '[*0:$]', '[*]', '[+]'):
            atoms = []
            result = leaves('(@(posedge clock) (p ##1 q)' + suffix + ') ##1 p', atoms)
            self.assertEqual(atoms, ['p','q'])
            self.assertIn(suffix, result)
            self.assertEqual(result.count('rvp_encoded_atom_0_x'), 2)
        with self.assertRaises(ValueError): leaves('p[*4:2]', [])

    def test_goto_counts_known_true_and_keeps_native_skip_semantics(self):
        for suffix in ('[->16]', '[->16:16]', '[->1:3]', '[->0]'):
            atoms=[]
            result=leaves('(@(posedge clock) (p & q)'+suffix+') ##1 r',atoms)
            self.assertEqual(atoms,['p & q','r'])
            self.assertIn('(rvp_encoded_atom_0_d && !rvp_encoded_atom_0_x)',result)
            self.assertIn(suffix,result)
            self.assertNotIn('[*0:$]',result)
        for bad in ('p[->3:1]','(p ##1 q)[->2]','p[->n]'):
            with self.assertRaises(ValueError): leaves(bad,[])

    def test_repeated_predicates_reuse_knownness_not_aliased_outputs(self):
        atoms = []
        result = leaves('p ##1 q ##1 p ##1 q', atoms)
        self.assertEqual(atoms, ['p','q'])
        self.assertEqual(result.count('rvp_encoded_atom_0_x'), 2)
        self.assertEqual(result.count('rvp_encoded_atom_1_x'), 2)

    def test_unbalanced_forms_fail_closed(self):
        for expression in ('(a', 'a)', 'a ##x b'):
            with self.assertRaises(ValueError):
                leaves(expression, [])


if __name__ == '__main__':
    unittest.main()
