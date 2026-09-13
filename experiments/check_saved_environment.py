"""Recheck saved Phase-2B metadata in a new directory, without model calls."""
import argparse
import json
from pathlib import Path
import sys
from environment_preflight import prepare, write_replay_manifest
from run_records import save


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--haven-root',type=Path,required=True)
    source = p.add_mutually_exclusive_group(required=True)
    source.add_argument('--stage1',type=Path)
    source.add_argument('--design',type=Path,help='physical environment preflight before any model work')
    p.add_argument('--top',help='original RTL top, required with --design')
    p.add_argument('--clock',help='primary RTL clock pin when haven.json omits it')
    p.add_argument('--reset',help='primary RTL reset pin when haven.json omits it')
    p.add_argument('--reset-active-low',action='store_true')
    p.add_argument('--out',type=Path,required=True)
    args = p.parse_args()
    sys.path.insert(0,str(args.haven_root.resolve()/'src'))
    if args.stage1:
        ir = args.stage1/'ir'
        task = json.loads((ir/'phase0_config.json').read_text())
        bp = json.loads((ir/'phase2b_blueprint.json').read_text())
    else:
        if not args.top: p.error('--design requires --top')
        task = json.loads((args.design/'haven.json').read_text())
        task.update(root=str(args.design.resolve()),module_name=args.top,
                    rtl_files=[str(p.resolve()) for p in sorted((args.design/'rtl').glob('*.v'))])
        if args.clock: task['clock'] = {'port':args.clock}
        if args.reset: task['reset'] = {'name':args.reset,'level':'low' if args.reset_active_low else 'high'}
        if not task.get('clock') or not task.get('reset'):
            p.error('pre-model design preflight needs explicit haven.json clock/reset metadata')
        bp = {'module_name':args.top,'clock':task['clock'],'reset':task['reset'],
              'bfm_configs':task.get('bfm_configs') or [],'io_specification':{},'ref_model':{}}
    task,bp = prepare(task,bp,args.out,Path(__file__).resolve().parent/'eda-shell')
    write_replay_manifest(task,bp,args.out/'replay')
    from haven.utils.template_engine import TemplateEngine
    from haven.utils.bfm_renderer import BFMRenderer
    from haven.dsl.schema import BFMConfig
    from haven.eda.vcs_utils import vcs_compile
    root = Path(__file__).resolve().parent
    code = {key:TemplateEngine().render(key,bp) for key in ('interface','top')}
    code.update(BFMRenderer().render_all([BFMConfig(**c) for c in bp.get('bfm_configs',[])]))
    module = bp['module_name']
    files = []
    for key,text in code.items():
        name = key if key.startswith('bfm_') else module+'_'+key
        file = args.out/(name+'.sv')
        file.write_text('`timescale 1ns/1ps\n'+text)
        files.append(str(file.resolve()))
    package = args.out/(module+'_pkg.sv')
    package.write_text('package '+module+'_pkg; endpackage\n')
    files.insert(0,str(package.resolve()))
    # Compile the original RTL and generated physical environment, not a fake
    # DUT or a hand-authored benchmark sequence. Runtime UVM is checked later.
    filelist = args.out/'environment.f'
    filelist.write_text('\n'.join(['+incdir+'+str(Path(task['root'])/'rtl'),
                                   *task['rtl_files'],*files])+'\n')
    config = json.loads((root/'designs/haven_eda.json').read_text())
    config['eda_env'] = {'shell':str(root/'eda-shell')}
    result = vcs_compile(str(filelist.resolve()),cwd=str(args.out.resolve()),config=config)
    (args.out/'compile.log').write_text(result['log'])
    save(args.out/'result.json',{'status':'compile_passed' if result['ok'] else 'compile_failed',
                               'environment':bp['environment_contract'],'model_calls':0,
                               'not_a_completed_experiment':True})
    if not result['ok']: raise ValueError('physical environment compilation failed; inspect compile.log')
    print(json.dumps({'status':'compile_passed','top':module,'model_calls':0}))


if __name__ == '__main__': main()
