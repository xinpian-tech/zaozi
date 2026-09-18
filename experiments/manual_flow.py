"""Author-in-the-loop diagnostic of the real paired flow, never a model benchmark.

Only the completion transport is replaced. Requests are written to a mailbox;
the operator supplies an OpenAI-style message with a matching request hash.
All parsing, skill dispatch, compilation, solving, sampling and replay remain real.
No provider credentials are loaded; other model entry points fail closed.
"""
import backend_imports
import argparse
import json
import os
from pathlib import Path
import sys
import time
from unittest.mock import patch

import coverage_flow as paired
import sequence_experiment as generation
from run_records import fingerprint, save, utc
from rvprobe.backend.process import run as run_worker


class ManualCompletion:
    def __init__(self, mailbox, wait_seconds=1800):
        self.mailbox = Path(mailbox)
        self.wait_seconds = wait_seconds
        self.count = 0

    def __call__(self, payload, timeout):
        if payload.get('model') != 'manual-author-debug':
            raise ValueError('manual transport must not impersonate a provider model')
        self.count += 1
        folder = self.mailbox/f'{self.count:04d}'
        folder.mkdir(parents=True,exist_ok=False)
        identity = fingerprint(payload)
        save(folder/'request.json',dict(payload=payload,sha256=identity,created_utc=utc(),
             diagnostic_only=True,remote_llm_requests=0,worker_pid=os.getpid()))
        print(f'MANUAL_REQUEST {folder}',flush=True)
        deadline = time.monotonic()+self.wait_seconds
        while not (folder/'response.json').is_file():
            if time.monotonic() >= deadline:
                raise RuntimeError('manual response deadline exceeded; no remote fallback')
            time.sleep(0.25)
        response = json.loads((folder/'response.json').read_text())
        if response.get('request_sha256') != identity:
            raise ValueError('manual response does not match the exact request')
        message = response['message']
        if message.get('role') != 'assistant':
            raise ValueError('manual response must be an assistant message')
        finish = 'tool_calls' if message.get('tool_calls') else 'stop'
        return {'id':f'manual-{self.count}','model':'manual-author-debug',
                'choices':[{'message':message,'finish_reason':finish}],
                # Actual Codex authoring usage is unavailable. Never invent a
                # zero model-token measurement or compare it against DeepSeek.
                'usage':{'prompt_tokens':None,'completion_tokens':None,'total_tokens':None}}


def worker_entry(command):
    """Only the normal two generation entry points may use this transport."""
    if len(command) < 2 or command[0] != sys.executable:
        raise ValueError('unexpected manual generation worker')
    script = Path(command[1]).resolve()
    if script == Path(generation.__file__).resolve():
        entry = generation.main
    elif script == Path(paired.__file__).resolve() and command[2:3] == ['request']:
        entry = paired.main
    else:
        raise ValueError('unexpected manual generation worker: '+str(script))
    if any(arg == '--env-file' or arg.startswith('--env-file=') for arg in command):
        raise ValueError('manual workers cannot load provider credentials')
    return entry


class ManualWorkers:
    """Keep process exit, timeout, logs and argument handling on the real path."""
    def __init__(self, mailbox, haven, wait_seconds=1800):
        self.mailbox, self.haven = Path(mailbox).resolve(), Path(haven).resolve()
        self.wait_seconds = wait_seconds
        self.count = 0

    def __call__(self, command, **kwargs):
        worker_entry(command)
        self.count += 1
        folder = self.mailbox/f'worker-{self.count:04d}'
        env = dict(kwargs.pop('env', os.environ))
        for key in ('RVPROBE_LLM_API_KEY','RVPROBE_LLM_BASE_URL','OPENAI_API_KEY','OPENAI_BASE_URL'):
            env.pop(key,None)
        env['PYTHONPATH'] = os.pathsep.join([str(self.haven/'src'),str(Path(__file__).resolve().parent)])
        return run_worker([sys.executable,str(Path(__file__).resolve()),'worker',
            '--mailbox',str(folder),'--wait-seconds',str(self.wait_seconds),'--',*command],
            env=env,**kwargs)


def worker(argv):
    parser=argparse.ArgumentParser(description='Credential-free manual completion worker')
    parser.add_argument('--mailbox',type=Path,required=True)
    parser.add_argument('--wait-seconds',type=float,default=1800)
    parser.add_argument('command',nargs=argparse.REMAINDER)
    args=parser.parse_args(argv)
    command=args.command[1:] if args.command[:1]==['--'] else args.command
    entry=worker_entry(command)
    from offline_validation import no_model_calls
    responder=ManualCompletion(args.mailbox,args.wait_seconds)
    with no_model_calls(), patch.object(generation,'send_completion',responder):
        return entry(command[2:])


def main():
    if sys.argv[1:2]==['worker']:
        return worker(sys.argv[2:])
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ('stage1-run','haven-root','out'):
        parser.add_argument('--'+key,type=Path,required=True)
    parser.add_argument('--rounds',type=int,default=3)
    parser.add_argument('--arm',choices=('both','haven','rvprobe'),default='both')
    parser.add_argument('--jg-time-limit',default='120s')
    parser.add_argument('--runtime-repairs',type=int,default=1)
    parser.add_argument('--seed',type=int,default=20260906)
    parser.add_argument('--boundary',choices=['independent-dut-v1'],default='independent-dut-v1')
    args=parser.parse_args()
    root=Path(__file__).resolve().parent
    out=args.out.resolve(); out.mkdir(parents=True,exist_ok=False)
    # Freeze the implementation before importing HAVEN. No source modifications
    # while a diagnostic is in progress can affect its compiled shared bench.
    from haven_snapshot import snapshot
    haven=snapshot(args.haven_root,out/'implementation/haven')
    paired.load_haven(haven)
    from offline_validation import no_model_calls
    stage=args.stage1_run.resolve()
    task=json.loads((stage/'ir/phase0_config.json').read_text())
    bp=json.loads((stage/'ir/phase2b_blueprint.json').read_text())
    record=dict(status='running',diagnostic_only=True,author='manual-author-debug',
                remote_llm_requests=0,deepseek_tokens=0,author_token_usage=None,
                started_utc=utc(),stage1=str(stage),formal_experiment=False,
                transport='manual-mailbox-subprocess-v2',supervisor_pid=os.getpid(),
                boundary=args.boundary,
                configuration={'rounds':args.rounds,'arm':args.arm,'sequences_per_intent':4,
                               'jg_time_limit':args.jg_time_limit,'runtime_repairs':args.runtime_repairs,
                               'request_retries':1,'seed':args.seed})
    save(out/'diagnostic.json',record)
    began=time.monotonic()
    dispatcher=ManualWorkers(out/'mailbox',haven)
    try:
        with no_model_calls():
            from environment_preflight import write_replay_manifest
            replay=write_replay_manifest(task,bp,out/'manifest',(Path(task['root'])/task['spec']).read_text(),boundary=args.boundary)
            paired.prepare(stage,haven,replay,out/'shared')
            with patch.object(paired,'run_process',dispatcher):
                code=paired.main(['run','--bundle',str(out/'shared/bundle.json'),
                    '--haven-root',str(haven),'--out',str(out/'paired'),'--arm',args.arm,
                    '--model','manual-author-debug','--rounds',str(args.rounds),
                    '--attempts','3','--request-retries','1','--runtime-repairs',str(args.runtime_repairs),
                    '--sequences-per-intent','4','--jg-time-limit',args.jg_time_limit,'--seed',str(args.seed),
                    '--isolate-sequences','--eda-shell',str(root/'eda-shell'),
                    '--eda-config',str(root/'designs/haven_eda.json')])
            record['status']='passed' if code==0 else 'failed'
    except Exception as error:
        record.update(status='failed',error=str(error))
    finally:
        record.update(finished_utc=utc(),elapsed_seconds=time.monotonic()-began,
                      manual_completion_calls=len(list((out/'mailbox').glob('worker-*/*/request.json'))),
                      generation_workers=dispatcher.count)
        save(out/'diagnostic.json',record)
    print(json.dumps(record))
    return int(record['status']!='passed')


if __name__=='__main__': raise SystemExit(main())
