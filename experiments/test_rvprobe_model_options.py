import argparse
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import sequence_experiment as generation
from rvprobe_model_options import add_options,cli,effective,record,token_limit,model_for
from rvprobe_skill import snapshot
from run_records import Records,totals


class GenerationBudget(unittest.TestCase):
    def test_regular_deepseek_override_never_changes_haven_model_or_budget(self):
        parser=argparse.ArgumentParser();add_options(parser)
        args=parser.parse_args(['--rvprobe-model','deepseek-v4-flash',
            '--rvprobe-reasoning-effort','high','--rvprobe-max-tokens','65536'])
        for _ in range(3):args=parser.parse_args(cli(args))
        args.model='deepseek-v4-flash-vision-exp'
        self.assertEqual(model_for(args),'deepseek-v4-flash')
        self.assertEqual(model_for(args,'haven'),'deepseek-v4-flash-vision-exp')
        self.assertEqual(record(args)['model'],'deepseek-v4-flash')
        self.assertEqual(record(args)['max_tokens'],65536)
        self.assertEqual(record(args)['reasoning_effort'],'high')

    def test_incremental_policy_forwarded_and_recorded(self):
        parser=argparse.ArgumentParser();add_options(parser)
        args=parser.parse_args(['--rvprobe-dialogue-policy','incremental'])
        for _ in range(3):args=parser.parse_args(cli(args))
        self.assertEqual(record(args)['dialogue_policy'],'incremental')

    def test_default_max_effort_does_not_override_token_or_timeout_limits(self):
        parser=argparse.ArgumentParser();add_options(parser)
        args=parser.parse_args([]);args.timeout=600
        self.assertEqual(cli(args),['--rvprobe-reasoning-effort','max'])
        self.assertEqual(record(args)['reasoning_effort'],'max')
        self.assertIsNone(record(args)['max_tokens'])
        self.assertEqual(record(args)['request_timeout_seconds'],600)

    def test_options_survive_runner_forwarding(self):
        parser=argparse.ArgumentParser();add_options(parser)
        flags=['--rvprobe-max-tokens','393216','--rvprobe-request-timeout','3600','--rvprobe-reasoning-effort','max']
        args=parser.parse_args(flags)
        for _ in range(3):args=parser.parse_args(cli(args))
        self.assertEqual(cli(args),flags)
        self.assertEqual(record(args)['request_timeout_seconds'],3600)
        for value in (0,-1,393217,True):
            with self.assertRaises((ValueError,argparse.ArgumentTypeError)):token_limit(value)

    def test_retrieval_budget_options_are_forwarded_and_recorded(self):
        parser=argparse.ArgumentParser();add_options(parser)
        args=parser.parse_args(['--rvprobe-evidence-steps','2','--rvprobe-evidence-tools','16',
                                '--rvprobe-retrieval-max-tokens','32768',
                                '--rvprobe-retrieval-reasoning-effort','low'])
        self.assertEqual(cli(args),['--rvprobe-reasoning-effort','max',
            '--rvprobe-evidence-steps','2','--rvprobe-evidence-tools','16',
            '--rvprobe-retrieval-max-tokens','32768','--rvprobe-retrieval-reasoning-effort','low'])
        options=record(args)
        self.assertEqual(options['retrieval_max_tokens'],32768)
        self.assertEqual(options['retrieval_reasoning_effort'],'low')

    def test_quality_policy_escalates_every_round_below_floor(self):
        parser=argparse.ArgumentParser();add_options(parser)
        args=parser.parse_args(['--rvprobe-dialogue-policy','incremental',
            '--rvprobe-reasoning-effort','low','--rvprobe-max-tokens','32768',
            '--rvprobe-evidence-steps','0','--rvprobe-evidence-tools','1',
            '--rvprobe-adaptive-quality-floor','95'])
        first=effective(args,{'score':70},1)
        self.assertTrue(first['quality_escalated'])
        self.assertEqual((first['max_tokens'],first['reasoning_effort']),(65536,'high'))
        self.assertEqual((first['evidence_steps'],first['evidence_tools']),(1,8))
        later=effective(args,{'score':94.5},2)
        self.assertTrue(later['quality_escalated'])
        self.assertEqual((later['max_tokens'],later['reasoning_effort']),(65536,'high'))
        self.assertEqual((later['evidence_steps'],later['evidence_tools']),(1,8))
        reached=effective(args,{'score':95},2)
        self.assertFalse(reached['quality_escalated'])

    def test_invalid_evidence_budgets_are_rejected_by_outer_runner(self):
        parser=argparse.ArgumentParser();add_options(parser)
        for arguments in (['--rvprobe-evidence-steps','-1'],
                          ['--rvprobe-evidence-tools','0']):
            with self.subTest(arguments=arguments), self.assertRaises(SystemExit):
                parser.parse_args(arguments)

    def test_provider_credentials_are_checked_without_loading_values(self):
        with TemporaryDirectory() as tmp, patch.dict('os.environ',{},clear=True):
            root=Path(tmp)
            good=root/'good.env';good.write_text('OPENAI_API_KEY=fake\nOPENAI_BASE_URL=https://invalid.test\n')
            generation.require_provider_credentials(good)
            self.assertNotIn('OPENAI_API_KEY',__import__('os').environ)
            bad=root/'bad.env';bad.write_text('TELEGRAM_BOT_TOKEN=fake\n')
            with self.assertRaisesRegex(RuntimeError,'live LLM invocation requires'):
                generation.require_provider_credentials(bad)

    def test_request_reaches_sender_with_max_effort_and_is_recorded(self):
        with TemporaryDirectory() as tmp:
            root=Path(tmp)
            args=SimpleNamespace(model='test',temperature=.3,timeout=3600,request_retries=1,
                rvprobe_skill_snapshot=snapshot(),max_tokens=393216)
            reply={'model':'test','choices':[{'message':{'content':'STOP'},'finish_reason':'stop'}],
                'usage':{'prompt_tokens':10,'completion_tokens':2,'total_tokens':12}}
            with patch.object(generation,'send_completion',return_value=reply) as send:
                raw,_=generation.request_model(args,'task',root,Records(root))
            payload,timeout=send.call_args.args
            self.assertEqual((raw,payload['max_tokens'],timeout),('STOP',393216,3600))
            self.assertEqual(payload['reasoning_effort'],'max')
            self.assertNotIn('thinking',payload)
            events=[json.loads(x) for x in (root/'events.jsonl').read_text().splitlines()]
            event=next(e for e in events if e['phase']=='model-request' and e['status']=='ok')
            self.assertEqual(event['requested_max_tokens'],393216)
            self.assertEqual(event['requested_reasoning_effort'],'max')
            self.assertEqual(event['request_timeout_seconds'],3600)
            self.assertEqual(totals(root)['usage_reported']['total_tokens'],12)

    def test_default_request_has_no_generation_limit(self):
        with TemporaryDirectory() as tmp:
            args=SimpleNamespace(model='test',temperature=.3,timeout=600,request_retries=1,
                rvprobe_skill_snapshot=snapshot())
            reply={'model':'test','choices':[{'message':{'content':'STOP'},'finish_reason':'stop'}],
                'usage':{'prompt_tokens':1,'completion_tokens':1,'total_tokens':2}}
            with patch.object(generation,'send_completion',return_value=reply) as send:
                generation.request_model(args,'task',Path(tmp),Records(tmp))
            self.assertNotIn('max_tokens',send.call_args.args[0])

    def test_haven_without_skill_keeps_original_request_even_with_rvprobe_settings(self):
        with TemporaryDirectory() as tmp:
            args=SimpleNamespace(model='test',temperature=.3,timeout=600,request_retries=1,
                reasoning_effort='max',max_tokens=393216)
            reply={'model':'test','choices':[{'message':{'content':'answer'},'finish_reason':'stop'}],
                'usage':{'prompt_tokens':1,'completion_tokens':1,'total_tokens':2}}
            with patch.object(generation,'send_completion',return_value=reply) as send:
                generation.request_model(args,'task',Path(tmp),Records(tmp))
            payload=send.call_args.args[0]
            self.assertNotIn('max_tokens',payload)
            self.assertNotIn('reasoning_effort',payload)


if __name__=='__main__':unittest.main()
