"""Recheck a frozen diagnostic candidate against the current native LTL adapter.

Never edits its UT, stimulus, or original artifacts; never calls a model.
Used to regression-test transport fixes before another full manual closed loop.
"""
import argparse
import hashlib
import json
import re
import time
from pathlib import Path
from collections import OrderedDict

from coverage_flow import prepare, HavenSimulation, load_haven
from environment_preflight import write_replay_manifest
from cycle_replay import load_config
from haven_shared import render_witness_sequence
from ltl_replay import attach
from run_records import save,utc


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ('run','stage1','haven-root','out'):
        parser.add_argument('--'+key,type=Path,required=True)
    parser.add_argument('--round',type=int,default=1)
    parser.add_argument('--arm',choices=['rvprobe','haven'],default='rvprobe')
    parser.add_argument('--keep-going',action='store_true',help='measure every saved candidate even when an earlier candidate fails')
    parser.add_argument('--round-directory',help='saved round directory, including an existing repair suffix')
    parser.add_argument('--boundary',choices=['independent-dut-v1'])
    parser.add_argument('--label',help='only recheck the named saved goal; no new stimulus generation')
    parser.add_argument('--zero-delays',action='store_true',help='diagnostic only: compare VCS zero-delay RTL semantics against the same frozen witness')
    parser.add_argument('--dump-waveform',action='store_true',help='dump the live DUT hierarchy for offline first-divergence analysis')
    parser.add_argument('--negative-drive',help='diagnostic mutation PORT=INTEGER; require original LTL rejection')
    args=parser.parse_args()
    if args.arm == 'haven' and (args.label or args.negative_drive):
        parser.error('label and negative-drive require RVProbe witness frames')
    began=time.monotonic()
    out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
    load_haven(args.haven_root)
    from offline_validation import no_model_calls
    with no_model_calls():
        ir=args.stage1/'ir'
        task=json.loads((ir/'phase0_config.json').read_text())
        bp=json.loads((ir/'phase2b_blueprint.json').read_text())
        config=write_replay_manifest(task,bp,out/'manifest',(Path(task['root'])/task['spec']).read_text(),boundary=args.boundary)
        bundle=prepare(args.stage1,args.haven_root,config,out/'shared')
        if args.dump_waveform:
            top = bundle['components']['top']
            if top.count('endmodule') != 1:
                raise ValueError('waveform diagnostic requires a single testbench top')
            bundle['components']['top'] = top.replace('endmodule', 'initial begin $dumpfile("dut.vcd"); $dumpvars(0, u_dut); end\nendmodule')
        design,_=load_config(config)
        mutation = None
        if args.negative_drive:
            port,value=args.negative_drive.split('=',1)
            widths={p.name:p.width for p in design.data_ports if p.direction=='input' and p.kind!='clock'}
            value=int(value,0)
            if port not in widths or not 0<=value<2**widths[port]:raise ValueError('invalid diagnostic drive mutation')
            if port in bundle['event_environment_policy']['retained_inputs']:raise ValueError('cannot mutate response-owned input')
            mutation=(port,value)
        round_name=args.round_directory or f'round-{args.round}'
        if not re.fullmatch(r'round-\d+(?:-repair-\d+)?',round_name):
            raise ValueError('invalid saved round directory')
        rd=args.run/'paired'/args.arm/round_name
        candidate=json.loads((rd/'candidate.json').read_text())
        goals={}
        for f in (rd/'generation').glob('attempt-*/solve/report.json'):
            goals.update({g['label']:g for g in json.loads(f.read_text())['goals']})
        groups=OrderedDict()
        for row in candidate['frames']: groups.setdefault(row['segment'],[]).append(row)
        if args.arm == 'rvprobe' and len(groups)!=len(candidate['sequences']): raise ValueError('candidate sequence/frame count differs')
        if args.arm == 'haven' and candidate['frames']:
            raise ValueError('HAVEN candidate unexpectedly contains formal witness frames')
        eda=json.loads((Path(__file__).parent/'designs/haven_eda.json').read_text())
        eda['eda_env']={'shell':str(Path(__file__).resolve().parent/'eda-shell')}
        if args.zero_delays:
            eda['eda_tools']['vcs']['flags'].append('+delay_mode_zero')
        sim=HavenSimulation(bundle,design,eda,20260906)
        result={'diagnostic_only':True,'remote_llm_requests':0,'started_utc':utc(),'sequences':[]}
        result.update(boundary=args.boundary,source_round=str(rd),selected_label=args.label,arm=args.arm,
                      candidate_sha256=hashlib.sha256((rd/'candidate.json').read_bytes()).hexdigest())
        result['zero_delays_diagnostic'] = args.zero_delays
        result['waveform_diagnostic'] = args.dump_waveform
        result['negative_drive'] = mutation
        selected_count = 0
        batches = zip(groups.values(),candidate['sequences']) if args.arm == 'rvprobe' else (([],s) for s in candidate['sequences'])
        for index,(rows,source) in enumerate(batches):
            label=re.search(r'class (\w+) extends',source)[1]
            if args.arm == 'rvprobe':
                matching=[g for name,g in goals.items() if f'_{name}_' in label]
                if len(matching)!=1: raise ValueError('ambiguous candidate goal')
                if args.label and matching[0]['label'] != args.label: continue
                attach(rows,matching[0])
            selected_count += 1
            if mutation:
                for row in rows:
                    if row['kind']=='witness':row['drive'][mutation[0]]=mutation[1]
            sequence=render_witness_sequence(design,rows,label,0) if args.arm == 'rvprobe' else source
            item={'index':index,'label':matching[0]['label'] if args.arm == 'rvprobe' else label}
            try:
                item.update(status='passed',coverage=sim(out/f'sequence-{index}',[sequence],rows))
                if mutation:item.update(status='failed',error='negative stimulus was incorrectly accepted')
            except Exception as error:
                item.update(status='rejected_as_expected' if mutation and 'original LTL goal did not hold' in str(error) else 'failed',error=str(error))
                if hasattr(error,'diagnostics'):
                    item['diagnostics']={k:v for k,v in error.diagnostics.items() if k!='tail'}
            result['sequences'].append(item)
            save(out/'progress.json',result)
            if item['status']=='failed' and not args.keep_going:break
        result.update(status='passed' if selected_count and len(result['sequences'])==(selected_count if args.label else len(candidate['sequences'])) and all(s['status']==('rejected_as_expected' if mutation else 'passed') for s in result['sequences']) else 'failed',finished_utc=utc())
        result['elapsed_seconds']=time.monotonic()-began
        save(out/'summary.json',result)
        print(json.dumps({k:v for k,v in result.items() if k!='sequences'}))
        return int(result['status']!='passed')

if __name__=='__main__':raise SystemExit(main())
