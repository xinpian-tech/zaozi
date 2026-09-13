"""Register existing Stage-1 environments; never generate or modify their stimuli."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

from frozen_stage1 import stage1_identity, load_fixed_setup
from haven_snapshot import snapshot
from run_records import save, utc


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--stages',type=Path,required=True)
    p.add_argument('--haven-root',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    args = p.parse_args()
    out = args.out.resolve(); out.mkdir(parents=True,exist_ok=False)
    haven = snapshot(args.haven_root,out/'implementation/haven')
    mapping = {}; results = {}
    for name, source in json.loads(args.stages.read_text()).items():
        stage = Path(source).resolve()
        if stage.is_file():
            original = json.loads(stage.read_text()); stage = Path(original['stage1'])
        else:
            original = {'stage1':str(stage)}
        try:
            identity = stage1_identity(stage,True)
            marker = stage/'manual-diagnostic.json'
            record = dict(status='stage1_ready',role='shared-stage1-environment',
                          created_utc=utc(),stage1=str(stage),haven_snapshot=str(haven),
                          original_record=str(Path(source).resolve()),prior_setup=original,
                          original_manual_provenance=json.loads(marker.read_text()) if marker.exists() else None,
                          stage1_model_calls=0,fixed_stage1=identity,
                          policy='Existing baseline and components shared verbatim by both Stage-2 arms; setup author is not a compared variable')
            path = out/name/'stage1-costs.json'; save(path,record)
            load_fixed_setup(path)
            mapping[name] = str(path)
            # Preparation validates fixed ports/clock/BFM/component contracts,
            # not the remote provider or coverage result. No model or simulator.
            code = ('import json,sys; from pathlib import Path; '
                    'from environment_preflight import write_replay_manifest; '
                    'from coverage_flow import prepare; '
                    's,h,o=map(Path,sys.argv[1:]); '
                    't=json.loads((s/"ir/phase0_config.json").read_text()); '
                    'b=json.loads((s/"ir/phase2b_blueprint.json").read_text()); '
                    'r=write_replay_manifest(t,b,o/"manifest",(Path(t["root"])/t["spec"]).read_text()); '
                    'prepare(s,h,r,o/"shared")')
            with (out/name/'prepare.log').open('x') as log:
                result = subprocess.run([sys.executable,'-c',code,str(stage),str(haven),str(out/name)],
                                        cwd=Path(__file__).parent,stdout=log,stderr=subprocess.STDOUT,timeout=180)
            results[name] = {'status':'prepared' if result.returncode==0 else 'prepare_failed'}
        except Exception as error:
            results[name] = {'status':'failed','error':str(error)}
        save(out/'stage1-map.json',mapping); save(out/'preflight.json',results)
        print(name,results[name],flush=True)


if __name__=='__main__': main()
