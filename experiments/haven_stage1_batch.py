#!/usr/bin/env python3
"""Prepare fresh shared HAVEN Stage-1 artifacts; NOT a completed paired experiment.

Every design runs in a fresh process with its own native token tracker. Never
discard BFMs, static pins or resets to bypass the paired adapter's scope checks.
"""
import backend_imports
import argparse
import json
import logging
from pathlib import Path
import sys
import time
from types import SimpleNamespace

from rvprobe.backend.process import run
from run_records import save, utc, framework_hashes, fresh_directory
from haven_shared import checkout_hashes
from design_inventory import DESIGNS

ROOT = Path(__file__).resolve().parent.parent


def resume_stage1(source, directory, config, before_protocol=False):
    """Reuse planning only; regenerate components against the repaired environment."""
    from haven.main import _load_state_from_ir, _save_token_summary
    from haven.graph.task_graph import build_generation_graph
    from haven.utils.output_manager import OutputManager
    from haven.utils.llm_client import reset_token_tracker
    from cycle_replay import digest
    source = Path(source).resolve()
    finalized = (source/'ir/phase2b_blueprint.json').is_file()
    names = ['phase0_config.json','phase1_structured_spec.json',
             'phase2b_blueprint.json' if finalized else 'phase2_blueprint.json','phase2b_protocol_flows.json']
    if before_protocol:
        if finalized: raise ValueError('cannot repeat protocol extraction after finalized planning')
        names = names[:-1]
    if any(not (source/'ir'/name).is_file() for name in names):
        raise ValueError('resume requires complete saved Phase 0–2B')
    task = json.loads((source/'ir'/names[0]).read_text())
    om = OutputManager(directory,task['module_name'])
    for name in names: save(om.ir_dir/name,json.loads((source/'ir'/name).read_text()))
    save(om.run_dir/'resumed-from.json',{'run':str(source),'resumed_at_phase':'2B' if before_protocol else 3 if finalized else '2B-finalization',
         'source_sha256':{str(source/'ir'/name):digest(source/'ir'/name) for name in names},
         'prior_cost_record':str(source.parents[1]/'stage1-costs.json'),
         'policy':'reuse model planning, never historical stimulus or coverage answers'})
    tracker = reset_token_tracker()
    state = _load_state_from_ir({'config':{**config,'task':task},'task':task,'output_manager':om},source/'ir',3)
    # The old loader renders BFMs before normalization. Phase 3 must render the
    # corrected source/mapping again; do not retain obsolete BFM components.
    state.pop('bfm_components',None)
    try:
        if before_protocol:
            from haven.graph.task_graph import node_protocol_flow
            state = node_protocol_flow(state)
        elif not finalized:
            from haven.graph.task_graph import finalize_protocol_flow
            from haven.phases.protocol_flow_extractor import ProtocolFlows
            state = finalize_protocol_flow(state,ProtocolFlows(**state['protocol_flows']))
        result = build_generation_graph(start_phase=3,until_phase=5).invoke(state)
        om.save_final(result.get('task',task)['module_name'],result.get('components',{}),result.get('sequences',[]),
                      bfm_components=result.get('bfm_components'))
    finally:
        _save_token_summary(om,tracker)


def worker(args):
    handoffs = args.out/'handoffs.json'
    if handoffs.is_file():
        assigned = json.loads(handoffs.read_text()).get(args.worker)
        if assigned:
            save(args.out/args.worker/'stage1-costs.json',{'status':'delegated_to_recorded_run',
                 'target':assigned,'paired_status':'pending','local_model_requests':0})
            return
    from sequence_experiment import load_env_file
    load_env_file(args.env_file)
    from haven_snapshot import snapshot
    directory = args.out / args.worker
    implementation = snapshot(args.haven_root,directory/'implementation/haven')
    sys.path.insert(0, str(implementation / 'src'))
    from haven.main import haven_run
    from haven.utils.llm_client import get_token_tracker
    from environment_preflight import install_stage1_preflight
    install_stage1_preflight(args.eda_shell)
    logging.basicConfig(level=logging.INFO)
    started, began = utc(), time.monotonic()
    result = {'status': 'failed', 'started_utc': started, 'model': args.model,
              'implementation_at_start': {'framework': framework_hashes(ROOT),
                                           'haven': checkout_hashes(implementation)},
              'haven_snapshot': str(implementation)}
    try:
        if args.resume_stage1:
            result['resumed_from'] = str(args.resume_stage1.resolve())
            resume_stage1(args.resume_stage1,directory/'stage1',json.loads((args.out/'config.json').read_text()),
                          before_protocol=args.resume_before_protocol)
        else:
            haven_run(SimpleNamespace(config=args.out / 'config.json', until=5, ablation=None,
                      designs=[args.haven_root / 'hdl' / args.worker], output_dir=directory / 'stage1'))
        paths = list((directory / 'stage1').glob('*/ir/phase5_compile_check_result.json'))
        if len(paths) != 1 or not json.loads(paths[0].read_text()).get('compile_passed'):
            raise ValueError('Stage-1 did not produce one passing compile result')
        stage = paths[0].parents[1]
        dsl_path = stage/'ir/phase4b_dsl_sequences.json'
        dsl = json.loads(dsl_path.read_text()) if dsl_path.is_file() else {}
        if not dsl.get('sequences') or not list((stage/'final').glob('sequence_*.sv')):
            raise ValueError('Stage-1 has no model-generated baseline sequences; empty compilation is not success')
        result.update(status='stage1_ready', stage1=str(paths[0].parents[1]),
                      paired_status='pending_adapter_validation')
    except Exception as error:
        result['error'] = str(error)
        raise
    finally:
        result.update(finished_utc=utc(), elapsed_seconds=time.monotonic()-began,
                      native_token_tracker=get_token_tracker().summary())
        save(directory / 'stage1-costs.json', result)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--haven-root', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--env-file', type=Path, required=True)
    p.add_argument('--eda-shell', type=Path, required=True)
    p.add_argument('--model', default='deepseek-v4-flash-vision-exp')
    p.add_argument('--worker', choices=DESIGNS)
    p.add_argument('--resume-stage1',type=Path,help='worker: reuse saved Phase 0–2B in a NEW Stage-1 run')
    p.add_argument('--resume-before-protocol',action='store_true',help='worker: saved model planning ends at Phase 2A')
    p.add_argument('--designs',nargs='+',choices=DESIGNS,default=list(DESIGNS))
    p.add_argument('--checkpoint-root',type=Path,help='reuse matching complete planning checkpoints')
    p.add_argument('--preflight', action='store_true')
    args = p.parse_args()
    args.haven_root, args.out = args.haven_root.resolve(), args.out.resolve()
    if args.worker:
        worker(args)
        return
    # Fail before any paid request if the previously broken dependency graph cannot import.
    sys.path.insert(0, str(args.haven_root / 'src'))
    from haven.graph.task_graph import build_generation_graph
    import shutil
    if not shutil.which('bwrap') or not args.eda_shell.is_file() or not args.env_file.is_file():
        raise ValueError('Missing sandbox, EDA wrapper or provider configuration')
    inventory = {}
    for name in args.designs:
        directory = args.haven_root / 'hdl' / name
        config = json.loads((directory / 'haven.json').read_text())
        inventory[name] = {'status': 'queued', 'paired_status': 'pending_adapter_validation',
                           'environment_requirements': {k: config[k] for k in
                               ('bfm_configs', 'static_signals', 'extra_resets') if config.get(k)}}
    if args.preflight:
        print(json.dumps({'status': 'preflight_passed', 'designs': inventory}, indent=2))
        return
    fresh_directory(args.out)
    save(args.out / 'manifest.json', {'model': args.model, 'temperature': 0.3,
         'framework': framework_hashes(ROOT), 'haven': checkout_hashes(args.haven_root),
         'scope': 'shared Stage-1 preparation only; paired results require adapter validation',
         'designs': args.designs,'checkpoint_root':str(args.checkpoint_root) if args.checkpoint_root else None})
    config = json.loads((ROOT / 'experiments/designs/haven_eda.json').read_text())
    config.update(eda_env={'shell': str(args.eda_shell.resolve())},
                  llm={'planning_model': args.model, 'coding_model': args.model, 'temperature': 0.3},
                  bayesian_opt={'enabled': False})
    config['eda_tools']['vc_formal'] = {'enabled': False}
    save(args.out / 'config.json', config)
    began = time.monotonic()
    summary = {'status': 'running', 'started_utc': utc(), 'designs': inventory}
    save(args.out / 'progress.json', summary)
    for name in args.designs:
        directory = args.out / name
        directory.mkdir()
        inventory[name]['status'] = 'running_stage1'
        save(args.out / 'progress.json', summary)
        with (directory / 'stage1.log').open('w') as log:
            try:
                extra = []
                if args.checkpoint_root:
                    paths = list((args.checkpoint_root/name/'stage1').glob('*/ir/phase2b_protocol_flows.json'))
                    if not paths:
                        paths = list((args.checkpoint_root/name/'stage1').glob('*/ir/phase2_blueprint.json'))
                        if paths: extra = ['--resume-before-protocol']
                    if len(paths)>1: raise ValueError('ambiguous saved planning checkpoint')
                    if paths: extra += ['--resume-stage1',str(paths[0].parents[1].resolve())]
                result = run([sys.executable, str(Path(__file__).resolve()),
                    '--haven-root', str(args.haven_root), '--out', str(args.out),
                    '--env-file', str(args.env_file), '--eda-shell', str(args.eda_shell),
                    '--model', args.model, '--worker', name,*extra], cwd=ROOT,
                    stdout=log, stderr=-2, timeout=3600)
                record = directory / 'stage1-costs.json'
                if record.exists():
                    inventory[name].update(json.loads(record.read_text()))
                else:
                    inventory[name].update(status='failed', error=f'worker exited {result.returncode}; usage unknown')
            except Exception as error:
                inventory[name].update(status='failed', error=str(error), token_usage='unknown')
        save(args.out / 'progress.json', summary)
    summary.update(status='stage1_batch_finished_not_paired', finished_utc=utc(),
                   elapsed_seconds=time.monotonic()-began)
    save(args.out / 'progress.json', summary)
    save(args.out / 'summary.json', summary)


if __name__ == '__main__':
    main()
