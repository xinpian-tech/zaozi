"""Offline real VCS randomizer positives/negative plus complete fixed-ALU flow."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace

from run_records import fresh_directory,save,utc
from sequence_framework import load_design,ROOT
from sv_constraint_baseline import sample,ConstraintError


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--out',required=True,type=Path)
    p.add_argument('--stage1-map',required=True,type=Path)
    p.add_argument('--haven-root',required=True,type=Path)
    p.add_argument('--yosys',required=True,type=Path)
    args=p.parse_args();root=fresh_directory(args.out.resolve())
    design=load_design(ROOT/'experiments/tests/fixtures/tiny_design.json')
    config={'environment':{'static':{},'extra_resets':[],'clocks':[{'port':'clk','period_ps':10000}]}}
    options=SimpleNamespace(eda_shell=ROOT/'experiments/eda-shell',sampling_seed=20260906)
    record={'started_utc':utc(),'diagnostic_only':True,'remote_llm_requests':0}
    try:
        positive={'cycles':3,'label':'generic_cross_time','intent':'synthetic solver regression',
                  'constraints':"payload[0]==8'h01; foreach (payload[i]) { if (i>0) payload[i]==payload[i-1]+1; valid[i]==1; }"}
        samples=sample(design,config,positive,root/'cross-time',options)
        assert all([int(s['drive']['payload'],16) for s in x['steps']]==[1,2,3] for x in samples)
        reduction={**positive,'constraints':"payload.sum() with (int'(item))==6; foreach (valid[i]) valid[i]==1;"}
        reduced=sample(design,config,reduction,root/'reduction',options)
        assert all(sum(int(s['drive']['payload'],16) for s in x['steps'])==6 for x in reduced)
        try:sample(design,config,{**positive,'constraints':'valid[0]==0; valid[0]==1;'},root/'negative',options)
        except ConstraintError as error:
            if 'randomize() failed' not in str(error):raise
            record['contradictory_constraints_rejected']=True
        else:raise AssertionError('inconsistent constraints accepted')
        # Generic input-only pattern, not a model result or benchmark answer.
        alu=Path(args.stage1_map).parent/'alu/stage1'
        bp=json.loads((alu/'ir/phase2b_blueprint.json').read_text())
        ports=bp['io_specification']['inputs']
        declarations=[]
        for port in ports:
            name=port['name']
            if name in (bp['clock']['port'],bp['reset']['name']):continue
            declarations.append(f"foreach ({name}[i]) {name}[i] == 0;")
        response=root/'offline-alu.json'
        save(response,{'intents':[{'label':'generic_zero_inputs','intent':'offline framework plumbing only',
            'cycles':2,'constraints':' '.join(declarations)}]})
        setups=json.loads(args.stage1_map.read_text())
        with (root/'flow.log').open('w') as log:
            subprocess.run([sys.executable,str(ROOT/'experiments/directed_experiment.py'),
                '--fixed-setup',setups['alu'],'--haven-root',str(args.haven_root),
                '--out',str(root/'alu'),'--method','directed_sv_constraint',
                '--encoded-witness-yosys',str(args.yosys),'--response-file',str(response),'--rounds','1'],
                cwd=ROOT,stdout=log,stderr=-2,check=True,timeout=1200)
        summary=json.loads((root/'alu/summary.json').read_text())
        assert summary['status']=='completed' and summary['rounds'][0]['added_sequences']==4
        record.update(status='passed',alu_coverage=summary['final']['score'])
    except Exception as error:
        record.update(status='failed',error=str(error));raise
    finally:
        record['finished_utc']=utc();save(root/'summary.json',record);print(json.dumps(record))


if __name__=='__main__':main()
