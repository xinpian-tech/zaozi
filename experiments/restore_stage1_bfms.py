"""Restore missing generated BFM artifacts, never replace model-generated code."""
import argparse
import json
from pathlib import Path
from cycle_replay import digest
from run_records import save, utc


def restore(stage):
    from haven.dsl.schema import BFMConfig
    from haven.utils.bfm_renderer import BFMRenderer
    stage = Path(stage).resolve()
    bp_path = stage/'ir/phase2b_blueprint.json'
    bp = json.loads(bp_path.read_text())
    sources = BFMRenderer().render_all([BFMConfig(**b) for b in bp.get('bfm_configs',[])])
    paths = [(stage/folder/(name+'.sv'),code) for folder in ('compile_check','final') for name,code in sources.items()]
    for path,code in paths:
        if path.exists() and path.read_text()!=code:
            raise ValueError('refusing to overwrite differing existing BFM: '+str(path))
    restored = []
    for path,code in paths:
        if not path.exists():
            path.parent.mkdir(parents=True,exist_ok=True)
            with path.open('x') as stream: stream.write(code)
            restored.append(str(path))
    save(stage/'bfm-artifact-restoration.json',{
        'utc':utc(),'reason':'resume skipped Phase-2B artifact rendering; no BFM behavior or stimulus change',
        'blueprint_sha256':digest(bp_path),'restored':restored,
        'artifact_sha256':{str(p):digest(p) for p,_ in paths}})
    return restored


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage',type=Path)
    print(json.dumps(restore(parser.parse_args().stage),indent=2))
