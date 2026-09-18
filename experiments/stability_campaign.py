"""Two fixed-version pilot repeats, an explicit gate, then the full RVProbe batch.

No automatic retries or model-authored Stage 1. Gate failure pauses the campaign
for diagnosis; it never silently changes budgets or cherry-picks passing runs.
"""
import argparse
import json
import sys
from pathlib import Path

from batch_lifecycle import current_owner
from design_inventory import DESIGNS
from evidence_packet import POLICY as DIALOGUE_POLICY
from frozen_stage1 import load_fixed_setup, verify_fixed_setup
from run_records import fresh_directory, framework_hashes, save, utc
from rvprobe.backend.process import run

ROOT = Path(__file__).resolve().parents[1]
PILOTS = ('uart', 'ethmac')
POLICY = dict(coverage_tolerance_pp=2.0, token_repeat_ratio=1.5,
              time_repeat_ratio=1.75, ethmac_token_vs_reference=0.85,
              uart_token_vs_reference=1.25,
              interpretation='engineering release gate, not statistical significance')


def metrics(batch, design):
    directory = Path(batch)/design/'flow/paired'
    summary = json.loads((directory/'summary.json').read_text())
    arm = summary['arms']['rvprobe']
    calls = [call for path in (directory/'rvprobe').glob('round-*/generation/attempt-*/dialogue-*.json')
             for call in json.loads(path.read_text())['calls']]
    costs = summary['costs']
    return dict(design=design, status=summary['status'], tokens=summary['tokens'],
        seconds=summary['elapsed_seconds'], coverage=arm['final']['score'],
        baseline=summary['baseline']['score'], rounds=len(arm['rounds']),
        added_sequences=sum(r['added_sequences'] for r in arm['rounds']),
        all_intents_satisfied=arm.get('all_intents_satisfied'),
        unresolved=arm.get('intent_outcomes', []), requests=costs['requests'],
        accounting_complete=costs['token_accounting_complete'],
        recorded_calls=len(calls), unique_call_ids=len({c['id'] for c in calls}),
        summed_tokens=sum(c.get('usage',{}).get('total_tokens') or 0 for c in calls),
        requested_models=sorted({c['requested_model'] for c in calls}),
        dialogue_policies=sorted({c.get('dialogue_policy','missing') for c in calls}),
        reasoning_history_characters=sum(c.get('history_reasoning_characters',0) for c in calls),
        cached_tokens=costs['usage_breakdown']['cached_tokens']['reported_tokens'])


def assess(repeats, references, designs=PILOTS):
    reasons = []
    for design in designs:
        rows = [repeat[design] for repeat in repeats]
        reference = references[design]
        for index, row in enumerate(rows, 1):
            prefix = f'{design} pilot-{index}: '
            if row['status'] != 'completed': reasons.append(prefix+'experiment not completed')
            if not row['accounting_complete'] or not (
                    row['requests']==row['recorded_calls']==row['unique_call_ids'] and
                    row['tokens']==row['summed_tokens'] and row['requests']>0):
                reasons.append(prefix+'incomplete or inconsistent usage accounting')
            if row['requested_models'] != ['deepseek-v4-flash-vision-exp']:
                reasons.append(prefix+'requested model differs')
            if row['dialogue_policies'] != [DIALOGUE_POLICY] or row['reasoning_history_characters']:
                reasons.append(prefix+'independent-evidence request invariant failed')
            if row['added_sequences']<=0 or row['coverage']<=row['baseline']:
                reasons.append(prefix+'no measured improvement over baseline')
            if row['coverage'] < reference['coverage']-POLICY['coverage_tolerance_pp']:
                reasons.append(prefix+'coverage regression exceeds tolerance')
            if row['tokens'] > reference['tokens']*POLICY[design+'_token_vs_reference']:
                reasons.append(prefix+'token cost exceeds reference gate')
        if max(r['coverage'] for r in rows)-min(r['coverage'] for r in rows)>POLICY['coverage_tolerance_pp']:
            reasons.append(design+': repeat coverage variation exceeds tolerance')
        for field, limit in [('tokens',POLICY['token_repeat_ratio']),('seconds',POLICY['time_repeat_ratio'])]:
            low=min(r[field] for r in rows);high=max(r[field] for r in rows)
            if low<=0 or high/low>limit: reasons.append(design+': repeat '+field+' variation exceeds tolerance')
    return dict(passed=not reasons, reasons=reasons, policy=POLICY, repeats=repeats, references=references)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('haven-root','env-file','stage1-map','yosys','work','archive','reference'):
        parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args()
    work,archive=args.work.resolve(),args.archive.resolve()
    if work==archive or work in archive.parents or archive in work.parents:
        parser.error('independent working and archive roots required')
    fresh_directory(work);fresh_directory(archive)
    frozen=framework_hashes(ROOT)
    owner=current_owner()
    state=dict(status='running',stage='preflight',started_utc=utc(),owner=owner,pid=owner['pid'],
        framework=frozen,policy=POLICY,work=str(work),archive=str(archive),batches={},
        arm='rvprobe',stage1_model_calls=0,full_designs=list(DESIGNS))
    def checkpoint():
        state['updated_utc']=utc()
        save(work/'campaign.json',state);save(archive/'campaign.json',state)
    def batch(name,designs,jobs):
        if framework_hashes(ROOT)!=frozen: raise RuntimeError('framework changed during campaign; use a new version')
        state['stage']=name
        state['batches'][name]=dict(work=str(work/name),archive=str(archive/name),status='running')
        checkpoint()
        command=[sys.executable,str(ROOT/'experiments/haven_design_batch.py'),
            '--haven-root',str(args.haven_root),'--env-file',str(args.env_file),
            '--stage1-map',str(args.stage1_map),'--encoded-witness-yosys',str(args.yosys),
            '--encoded-witness-time-limit','120s','--designs',*designs,'--arm','rvprobe',
            '--jobs',str(jobs),'--out',str(work/name),'--archive-root',str(archive/name),'--relocate-completed']
        if name.startswith('pilot-'):
            command += ['--pilot-reference',str(args.reference),
                        '--stop-new-model-requests',str(work/(name+'-stop-new-model-requests.json'))]
        with (archive/(name+'.log')).open('x') as log:
            result=run(command,cwd=ROOT,stdout=log,stderr=-2,timeout=72*3600)
        if result.returncode: raise RuntimeError(name+': batch process failed; inspect retained log')
        outcome=json.loads((work/name/'summary.json').read_text())
        state['batches'][name]['status']=outcome['status']
        checkpoint()
        if framework_hashes(ROOT)!=frozen: raise RuntimeError('framework changed during campaign; results not one version')
        return outcome
    checkpoint()
    try:
        ready=json.loads(args.stage1_map.read_text())
        for design in DESIGNS:
            _, identity=load_fixed_setup(Path(ready[design]))
            verify_fixed_setup(identity)
        references={name:metrics(args.reference,name) for name in PILOTS}
        repeats=[]
        for name in ('pilot-1','pilot-2'):
            outcome=batch(name,PILOTS,2)
            if outcome['status']!='finished':
                raise RuntimeError(name+': incomplete pilot; inspect failures before further model spending')
            repeats.append({design:metrics(work/name,design) for design in PILOTS})
            health=assess([repeats[-1]],references)
            save(archive/(name+'-health.json'),health)
            if not health['passed']:
                state.update(status='needs_diagnosis',reasons=health['reasons'])
                return 2
        gate=assess(repeats,references)
        save(archive/'gate.json',gate);save(work/'gate.json',gate)
        if not gate['passed']:
            state.update(status='needs_diagnosis',stage='gate',reasons=gate['reasons'])
            return 2
        outcome=batch('full-16',DESIGNS,3)
        state.update(status=('completed' if outcome['status']=='finished' else 'finished_with_outcomes'),
                     stage='full-16',full_batch_status=outcome['status'])
    except Exception as error:
        state.update(status='needs_diagnosis',error=str(error))
        return 2
    finally:
        state['finished_utc']=utc();checkpoint()
    return 0


if __name__=='__main__':
    raise SystemExit(main())
