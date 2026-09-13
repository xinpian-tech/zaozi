"""Fixed shared setup cannot be generated, replaced or silently edited by a run."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

from frozen_stage1 import load_fixed_setup, verify_fixed_setup, admit_shared_environment
from run_records import save


class FrozenStage1Tests(unittest.TestCase):
    def fixture(self,root):
        stage=root/'stage'; rtl=root/'dut.v'; rtl.write_text('module dut; endmodule\n')
        save(stage/'ir/phase0_config.json',{'module_name':'dut','root':str(root),'rtl_files':['dut.v']})
        save(stage/'ir/phase2b_blueprint.json',{'module_name':'dut'})
        save(stage/'ir/phase4b_dsl_sequences.json',{'module_name':'dut','sequences':[{'name':'base'}]})
        save(stage/'ir/phase5_compile_check_result.json',{'compile_passed':True})
        (stage/'final').mkdir()
        (stage/'final/sequence_1.sv').write_text('class base; endclass\n')
        (root/'haven/src/haven/eda').mkdir(parents=True)
        (root/'haven/src/haven/eda/urg_utils.py').write_text('value = 1\n')
        (root/'haven/src/haven/example.py').write_text('value = 1\n')
        record=root/'setup.json'
        save(record,{'status':'stage1_ready','model':'independent-setup-author',
                     'stage1':str(stage),'haven_snapshot':str(root/'haven')})
        return record

    def test_author_is_independent_and_artifact_changes_are_rejected(self):
        for changed in ('stage/final/sequence_1.sv','dut.v','haven/src/haven/example.py','setup.json'):
            with self.subTest(changed=changed),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp); record=self.fixture(root)
                setup,identity=load_fixed_setup(record)
                self.assertEqual(setup['model'],'independent-setup-author')
                verify_fixed_setup(identity)
                path=root/changed; path.write_text(path.read_text()+'\n')
                with self.assertRaisesRegex(ValueError,'changed'):
                    verify_fixed_setup(identity)

    def test_added_component_and_diagnostic_relabeling_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); record=self.fixture(root)
            _,identity=load_fixed_setup(record)
            (root/'stage/final/new_driver.sv').write_text('module injected; endmodule\n')
            with self.assertRaisesRegex(ValueError,'changed'): verify_fixed_setup(identity)
            save(root/'stage/manual-diagnostic.json',{'diagnostic_only':True})
            with self.assertRaisesRegex(ValueError,'diagnostic'): load_fixed_setup(record)

    def test_missing_setup_never_starts_a_worker_or_creates_run(self):
        import haven_design_batch as batch
        import haven_four_paired as four
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); mapping=root/'map.json'; save(mapping,{})
            common=['--haven-root',tmp,'--env-file',str(root/'unused.env'),'--out',str(root/'out')]
            with patch.object(batch,'run') as run,patch('sys.argv',[
                    'batch',*common,'--stage1-map',str(mapping),'--designs','alu']):
                with self.assertRaisesRegex(ValueError,'frozen shared Stage-1'): batch.main()
                run.assert_not_called()
            with patch.object(four.subprocess,'run') as run,patch('sys.argv',[
                    'four',*common,'--stage1-root',str(root/'missing')]):
                with self.assertRaises(FileNotFoundError): four.main()
                run.assert_not_called()
            self.assertFalse((root/'out').exists())

    def test_shared_manual_environment_requires_explicit_admission_and_preserves_origin(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); record=self.fixture(root)
            marker=root/'stage/manual-diagnostic.json'; save(marker,{'author':'human','diagnostic_only':True})
            with self.assertRaisesRegex(ValueError,'diagnostic'): load_fixed_setup(record)
            setup=json.loads(record.read_text()); setup['role']='shared-stage1-environment'; save(record,setup)
            _,identity=load_fixed_setup(record)
            self.assertIn(str(marker),identity['sha256'])
            bundle={'sources':identity['sha256'], 'manual_baseline_provenance':{'author':'human'}}
            admitted=admit_shared_environment(bundle,identity)
            self.assertEqual(admitted['provenance'],{'author':'human'})
            self.assertTrue(json.loads(marker.read_text())['diagnostic_only'])
            bundle['sources']={str(root/'other/ir/phase0_config.json'):'wrong'}
            with self.assertRaisesRegex(ValueError,'match'): admit_shared_environment(bundle,identity)

    def test_removed_generation_options_are_rejected(self):
        import haven_design_batch as batch
        from contextlib import redirect_stderr
        import io
        for option in (['--prepare-stage1'],['--checkpoint-map','old.json']):
            with patch('sys.argv',['batch','--haven-root','unused','--env-file','unused',
                       '--out','unused','--stage1-map','unused','--designs','alu',*option]), \
                    patch.object(batch,'run') as run,redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit): batch.main()
                run.assert_not_called()

    def test_runner_dispatches_only_coverage_and_preserves_source(self):
        import haven_design_batch as batch
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); record=self.fixture(root); mapping=root/'map.json'; save(mapping,{'alu':str(record)})
            _,before=load_fixed_setup(record)
            commands=[]
            def execute(argv,**kwargs):
                commands.append(argv)
                out=Path(argv[argv.index('--out')+1])
                save(out/'progress.json',{'status':'completed','summary':'fixture'})
                return SimpleNamespace(returncode=0)
            with patch('sys.argv',['batch','--haven-root',str(root/'haven'),'--env-file','unused',
                       '--out',str(root/'run'),'--stage1-map',str(mapping),'--designs','alu']), \
                    patch.object(batch,'run',side_effect=execute),patch.dict('os.environ',{}):
                batch.main()
            self.assertEqual([Path(c[1]).name for c in commands],['haven_event_paired.py'])
            verify_fixed_setup(before)
            report=json.loads((root/'run/summary.json').read_text())
            self.assertEqual(report['stage1_model_calls'],0)
            self.assertEqual(report['shared_setup_mode'],'frozen')
            self.assertEqual(report['status'],'finished')


if __name__=='__main__': unittest.main()
