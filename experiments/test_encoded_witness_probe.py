import unittest
from encoded_witness_probe import leaves, diagnostic_config, terminal_result, preference_ports, distinct_input_constraint
from types import SimpleNamespace
from pathlib import Path
from encoded_initialization import materialize_initializers, global_ff_commands, expose_collision_guards, preserve_single_driver_masks, BOOT, GLOBAL_CLOCK


class ExplicitInitialization(unittest.TestCase):
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
        for expression in ('$past(a)', 'a |-> b', 'a intersect b', 'a[*2]',
                           'a ##[1:$] b', '@(negedge clock) a', '(a ##1 b) && c'):
            with self.subTest(expression=expression):
                with self.assertRaises(ValueError):
                    leaves(expression, [])

    def test_unbalanced_forms_fail_closed(self):
        for expression in ('(a', 'a)', 'a ##x b'):
            with self.assertRaises(ValueError):
                leaves(expression, [])


if __name__ == '__main__':
    unittest.main()
