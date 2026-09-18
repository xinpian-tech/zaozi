import argparse
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest

from run_records import save
from rvprobe_model_options import add_options,cli,record
from supplement_campaign import VARIANTS,DEFAULT_VARIANTS,command,cell_metrics,export,retained_cells


class SupplementCampaign(unittest.TestCase):
    def test_cancelled_variant_is_not_default_or_reused(self):
        self.assertNotIn('directed_sv_constraint',DEFAULT_VARIANTS)
        prior={'cells':{
            'directed_sv_constraint/alu':{'status':'failed','storage_status':'relocated'},
            'rvprobe_full/alu':{'status':'completed','storage_status':'relocated'},
            'rvprobe_no_diagnostics/alu':{'status':'not_started'}}}
        keys=[v+'/alu' for v in DEFAULT_VARIANTS]
        self.assertEqual(set(retained_cells(prior,keys)),{'rvprobe_full/alu'})
        prior['cells']['rvprobe_no_diagnostics/alu']['status']='running'
        with self.assertRaisesRegex(ValueError,'safely archived'):retained_cells(prior,keys)

    def test_commands_freeze_shared_budgets_and_no_haven_calls(self):
        args=SimpleNamespace(env_file=Path('/secret.env'),yosys=Path('/yosys'))
        setup={'stage1':'/stage','haven_snapshot':'/haven'}
        for variant in VARIANTS:
            cmd=command(variant,setup,'/fixed.json',Path('/out'),args)
            self.assertNotIn('haven_design_batch.py',cmd)
            if variant=='directed_sv_constraint':
                self.assertIn('--fixed-rounds',cmd)
                self.assertEqual(cmd[cmd.index('--max-tokens')+1],'393216')
            else:
                self.assertEqual(cmd[cmd.index('--arm')+1],'rvprobe')
                self.assertIn('--rvprobe-fixed-rounds',cmd)
                self.assertEqual(cmd[cmd.index('--rvprobe-feedback-mode')+1],variant.removeprefix('rvprobe_'))
                self.assertEqual(cmd[cmd.index('--rvprobe-dialogue-policy')+1],'staged')

    def test_feedback_options_survive_three_runner_hops(self):
        parser=argparse.ArgumentParser();add_options(parser)
        for mode in ('full','no_diagnostics','no_coverage'):
            args=parser.parse_args(['--rvprobe-feedback-mode',mode,'--rvprobe-fixed-rounds'])
            for _ in range(3):args=parser.parse_args(cli(args))
            self.assertEqual(record(args)['feedback_mode'],mode)
            self.assertTrue(record(args)['fixed_rounds'])

    def test_failed_cells_keep_baseline_or_last_valid_not_best(self):
        for variant in VARIANTS:
            with TemporaryDirectory() as tmp:
                directory=Path(tmp)
                root=directory/('experiment' if variant=='directed_sv_constraint' else 'flow/paired')
                result={'status':'failed','baseline':{'score':20},'rounds':[],
                    'best':{'score':99},'error':'provider truncated'}
                def write():
                    save(root/'summary.json',result if variant=='directed_sv_constraint' else {
                        'status':'failed','baseline':result['baseline'],'arms':{'rvprobe':result}})
                write();value=cell_metrics(directory,variant)
                self.assertEqual(value['coverage'],20);self.assertTrue(value['baseline_only'])
                result['final']={'score':31};result['rounds']=[{'added_sequences':4,'coverage':{'score':31}}]
                write();value=cell_metrics(directory,variant)
                self.assertEqual(value['coverage'],31);self.assertEqual(value['accepted_rounds'],1)
                export(directory,{'status':'finished','protocol':{},'cells':{variant+'/alu':value}})
                self.assertTrue((directory/'results.csv').is_file())


if __name__=='__main__':unittest.main()
