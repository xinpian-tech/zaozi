"""No-network generation smoke: same LTL, real compiler/JG, feedback redaction."""
import argparse
import json
from pathlib import Path
from unittest.mock import patch

import sequence_experiment as generation
from run_records import fresh_directory,save,utc
from sequence_framework import load_design


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--prepared-direct-run',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    args=p.parse_args();out=fresh_directory(args.out.resolve())
    root=args.prepared_direct_run.resolve()
    summary=json.loads((root/'summary.json').read_text())
    modinfo=summary['baseline']['modinfo']
    design=load_design(root/'manifest-io/design.json')
    port=next(p for p in design.data_ports if p.direction=='input')
    body=f'Gen({port.name} === {port.name}, "offline_plumbing")'
    feedback=out/'feedback.json'
    save(feedback,{'score':91.123456,'gaps':[{'type':'line','text':'SECRET_COVERAGE'}],
        'intent_batch_limit':4,'runtime_failure':{'error':'REPLAY_DIAGNOSTIC'},
        'rejected_model_candidate':{'ltl':body}})
    report=dict(started_utc=utc(),diagnostic_only=True,remote_llm_requests=0,variants={})
    def no_network(*a,**kw):raise AssertionError('offline smoke attempted a network request')
    try:
        for mode in ('full','no_diagnostics','no_coverage'):
            count=0
            def fake_request(options,prompt,directory,records):
                nonlocal count
                count+=1
                evidence=json.dumps(options.task_context.initial_evidence())
                if count==1:
                    if mode=='no_coverage':
                        assert 'SECRET_COVERAGE' not in evidence and '91.123456' not in evidence
                        assert 'REPLAY_DIAGNOSTIC' in evidence
                    if mode=='no_diagnostics':assert 'REPLAY_DIAGNOSTIC' not in evidence
                    return 'invalid response envelope',{}
                if mode=='no_diagnostics':
                    assert 'Detailed diagnostics are withheld' in prompt
                    assert 'Detailed diagnostics are withheld' in json.dumps(options.task_context.dispatch('read_diagnostics',{}))
                return body,{}
            with patch.object(generation,'request_model',fake_request),patch.object(generation,'send_completion',no_network):
                code=generation.main(['--design',str(root/'manifest-io/design.json'),
                    '--replay-config',str(root/'manifest-io/replay.json'),'--modinfo',modinfo,
                    '--out',str(out/mode),'--feedback-file',str(feedback),
                    '--feedback-mode',mode,'--fixed-opportunities','--attempts','2'])
            result=json.loads((out/mode/'summary.json').read_text())
            assert code==0 and count==2,(mode,code,result.get('error'))
            assert any(g['status']=='generated' and Path(g['witnessFile']).is_file()
                       for g in result['result']['goals']),result['result']
            report['variants'][mode]=dict(status='passed',attempts=count)
        report['status']='passed'
    except Exception as error:
        report.update(status='failed',error=str(error));raise
    finally:
        report['finished_utc']=utc();save(out/'summary.json',report);print(json.dumps(report))


if __name__=='__main__':main()
