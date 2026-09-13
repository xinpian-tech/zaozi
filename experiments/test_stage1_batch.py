"""Checkpoint continuation tests, with no EDA/model calls."""
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from haven_stage1_batch import resume_stage1
from run_records import save
from haven_event_paired import validate_scope
from environment_preflight import install_stage1_preflight
from haven_snapshot import snapshot
from repair_shared_driver import validate_replacement


class Stage1ResumeTests(unittest.TestCase):
    def test_coverage_entry_never_implicitly_regenerates_shared_environment(self):
        from haven_design_batch import require_frozen_stage1
        with self.assertRaisesRegex(ValueError,'frozen shared Stage-1'):
            require_frozen_stage1(['first','second'],{'first':'record'})
        require_frozen_stage1(['first'],{'first':'record'})
        with self.assertRaises(TypeError):
            require_frozen_stage1(['first'],{},prepare_stage1=True)

    def test_resume_architecture_repeats_only_missing_protocol_call(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); source=root/'old/design/stage1/run'
            for name,value in {'phase0_config.json':{'module_name':'probe'},
                               'phase1_structured_spec.json':{},'phase2_blueprint.json':{}}.items():
                save(source/'ir'/name,value)
            om=SimpleNamespace(run_dir=root/'new/run',ir_dir=root/'new/run/ir',save_final=Mock())
            state={'task':{'module_name':'probe'}}
            protocol=Mock(side_effect=lambda s:s)
            build=Mock(return_value=SimpleNamespace(invoke=Mock(return_value=state)))
            modules={
                'haven.main':SimpleNamespace(_load_state_from_ir=Mock(return_value=state),_save_token_summary=Mock()),
                'haven.graph.task_graph':SimpleNamespace(build_generation_graph=build,node_protocol_flow=protocol),
                'haven.utils.output_manager':SimpleNamespace(OutputManager=Mock(return_value=om)),
                'haven.utils.llm_client':SimpleNamespace(reset_token_tracker=Mock())}
            with patch.dict('sys.modules',modules):
                resume_stage1(source,root/'new',{},before_protocol=True)
            protocol.assert_called_once_with(state)
            build.assert_called_once_with(start_phase=3,until_phase=5)
            self.assertEqual(json.loads((om.run_dir/'resumed-from.json').read_text())['resumed_at_phase'],'2B')

    def test_shared_driver_repair_cannot_change_class_dispatch_or_reporting(self):
        before = ('class probe_driver extends uvm_driver; '
                  'probe_env_dispatch_side_requests.get(req); '
                  'vif.side_i = 0; probe_env_dispatch_side_done.put(1); endclass')
        validator = Mock(return_value=[])
        bp = {'topology':{'agents':[{'name':'side'}]}}
        with patch.dict('sys.modules',{'haven.utils.environment_contract':SimpleNamespace(validate_driver_ownership=validator)}):
            validate_replacement(before,before,'side__driver',bp)
            for invalid in (before.replace('probe_driver','different_driver'),
                            before.replace('probe_env_dispatch_side_done.put(1);',''),
                            before.replace('endclass','force vif.side_i = 0; endclass'),
                            before.replace('endclass','uvm_report_server::get_server(); endclass')):
                with self.assertRaises(ValueError):
                    validate_replacement(before,invalid,'side__driver',bp)
            validator.return_value = ['foreign pin write']
            with self.assertRaisesRegex(ValueError,'foreign pin'):
                validate_replacement(before,before,'side__driver',bp)

    def test_snapshot_freezes_implementation_without_provider_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root/'source'
            save(source/'src/haven/eda/urg_utils.py',{'fixture':'implementation'})
            save(source/'.env',{'fixture':'must not be copied'})
            target = snapshot(source,root/'frozen')
            self.assertFalse((target/'.env').exists())
            before = (target/'src/haven/eda/urg_utils.py').read_text()
            save(source/'src/haven/eda/urg_utils.py',{'fixture':'changed'})
            self.assertEqual((target/'src/haven/eda/urg_utils.py').read_text(),before)
            with self.assertRaises(FileExistsError):
                snapshot(source,target)

    def test_preflight_renders_bfms_after_native_parameter_overrides(self):
        import types
        haven = types.ModuleType('haven')
        haven.graph = types.ModuleType('haven.graph')
        graph = types.ModuleType('haven.graph.task_graph')
        haven.graph.task_graph = graph
        def original(state):
            state['blueprint']['bfm_configs'] = [{'protocol':'generic','params':{'divider':4}}]
            return state
        graph.node_testbench_gen = original
        graph.finalize_protocol_flow = Mock()
        render = Mock(return_value={'bfm_generic':'rendered'})
        modules = {'haven':haven,'haven.graph':haven.graph,'haven.graph.task_graph':graph,
                   'haven.dsl.schema':SimpleNamespace(BFMConfig=lambda **kw:kw),
                   'haven.utils.bfm_renderer':SimpleNamespace(BFMRenderer=lambda:SimpleNamespace(render_all=render))}
        om = SimpleNamespace(run_dir=Path('/unused'),save_ir_json=Mock())
        state = {'task':{},'blueprint':{},'config':{},'output_manager':om}
        with patch.dict('sys.modules',modules), patch('environment_preflight.prepare',return_value=({'updated':True},{})):
            install_stage1_preflight(Path('/eda'))
            result = graph.node_testbench_gen(state)
        self.assertEqual(result['bfm_components'],{'bfm_generic':'rendered'})
        self.assertEqual(result['config']['task'],{'updated':True})
        render.assert_called_once_with([{'protocol':'generic','params':{'divider':4}}])

    def test_bfm_scope_never_silently_expands(self):
        validate_scope({'bfm_configs': []})
        bp = {'bfm_configs':[{'protocol':'uart_serial'}],
              'environment_contract':{'version':'pin-ownership-v1','bfm_owned':{'rx':'bfm:uart_serial'}}}
        self.assertEqual(validate_scope(bp)['retained_inputs'],[])
        bp['bfm_configs'] = [{'protocol':'mii_phy'}]
        with self.assertRaisesRegex(ValueError, 'MII adapter requires explicit signal roles'):
            validate_scope(bp)

    def exercise(self, failure=False, missing=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'old/design/stage1/run'
            names = {'phase0_config.json': {'module_name': 'probe'},
                     'phase1_structured_spec.json': {'functions': []},
                     'phase2b_blueprint.json': {'module_name': 'probe'},
                     'phase2b_protocol_flows.json': {'protocol': 'example'}}
            for name, value in names.items():
                if missing and name == 'phase2b_protocol_flows.json':
                    continue
                save(source / 'ir' / name, value)
            om = SimpleNamespace(run_dir=root/'new/run', ir_dir=root/'new/run/ir', save_final=Mock())
            loader = Mock(return_value={'bfm_components': {'obsolete': 'source'}, 'task': names['phase0_config.json']})
            tracker, summarize = object(), Mock()
            invoke = Mock(side_effect=ValueError('component failed')) if failure else Mock(return_value={
                'components': {'driver': 'new'}, 'sequences': ['new sequence'], 'bfm_components': {'new': 'bfm'}})
            graph = Mock(return_value=SimpleNamespace(invoke=invoke))
            modules = {
                'haven.main': SimpleNamespace(_load_state_from_ir=loader, _save_token_summary=summarize),
                'haven.graph.task_graph': SimpleNamespace(build_generation_graph=graph),
                'haven.utils.output_manager': SimpleNamespace(OutputManager=Mock(return_value=om)),
                'haven.utils.llm_client': SimpleNamespace(reset_token_tracker=Mock(return_value=tracker)),
            }
            with patch.dict('sys.modules', modules):
                if failure or missing:
                    with self.assertRaisesRegex(ValueError, 'Phase 0|component failed'):
                        resume_stage1(source, root/'new', {'llm': {'coding_model': 'fixed'}})
                else:
                    resume_stage1(source, root/'new', {'llm': {'coding_model': 'fixed'}})
            if missing:
                invoke.assert_not_called()
                return
            graph.assert_called_once_with(start_phase=3, until_phase=5)
            self.assertNotIn('bfm_components', invoke.call_args.args[0])
            summarize.assert_called_once_with(om, tracker)
            provenance = json.loads((om.run_dir/'resumed-from.json').read_text())
            self.assertEqual(provenance['prior_cost_record'], str(root/'old/design/stage1-costs.json'))
            self.assertEqual(len(provenance['source_sha256']), 4)
            for name, value in names.items():
                self.assertEqual(json.loads((source/'ir'/name).read_text()), value)
                self.assertEqual(json.loads((om.ir_dir/name).read_text()), value)
            if not failure:
                om.save_final.assert_called_once_with('probe', {'driver':'new'}, ['new sequence'],
                                                      bfm_components={'new':'bfm'})

    def test_resume_regenerates_components_and_preserves_source(self):
        self.exercise()

    def test_failed_resume_still_saves_usage(self):
        self.exercise(failure=True)

    def test_incomplete_checkpoint_never_runs_model(self):
        self.exercise(missing=True)


if __name__ == '__main__':
    unittest.main()
