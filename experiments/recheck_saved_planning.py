"""Offline replay of saved model planning through the shared environment preflight.

No new planning, stimulus, components or LTL are authored. This checks the stage
that failed, not a completed experiment. Original artifacts and costs stay intact.
"""
import argparse
from copy import deepcopy
import json
from pathlib import Path
import sys
import time

from run_records import save, utc, framework_hashes
from cycle_replay import digest


def recover_blueprint(raw):
    """Undo only a saved typed response envelope plus native carried metadata."""
    from haven.utils.json_output import loads
    if raw.get('type') != 'json_object':
        return deepcopy(raw)
    carried=set(raw)-{'type','content'}
    if carried-{'clock','reset','fsm_info','io_specification','ref_model'}:
        raise ValueError('ambiguous saved architecture envelope')
    result=loads(json.dumps({k:raw[k] for k in ('type','content')}))
    result.update({k:deepcopy(raw[k]) for k in carried})
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('previous','haven-root','out'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--designs',nargs='+',required=True)
    args=parser.parse_args()
    sys.path.insert(0,str(args.haven_root.resolve()/'src'))
    from offline_validation import no_model_calls
    from haven.utils.output_manager import OutputManager
    from haven.phases.protocol_flow_extractor import ProtocolFlows
    from haven.utils.template_engine import TemplateEngine
    from environment_preflight import install_stage1_preflight, prepare
    import haven.graph.task_graph as graph
    eda=Path(__file__).resolve().parent/'eda-shell'
    install_stage1_preflight(eda)
    args.out.mkdir(parents=True,exist_ok=False)
    began=time.monotonic()
    report=dict(status='running',started_utc=utc(),new_llm_tokens=0,diagnostic_only=True,
                framework=framework_hashes(Path(__file__).resolve().parents[1]),designs={})
    with no_model_calls():
        for name in args.designs:
            entry=report['designs'][name]={'status':'running'}
            started=time.monotonic()
            try:
                stages=list((args.previous/name/'setup'/name/'stage1').glob('*/ir/phase2_blueprint.json'))
                if len(stages)!=1: raise ValueError('expected one saved architecture per design')
                ir=stages[0].parent
                names=('phase0_config','phase2_blueprint','phase2b_protocol_flows')
                saved={key:json.loads((ir/(key+'.json')).read_text()) for key in names}
                entry['source_sha256']={str(ir/(k+'.json')):digest(ir/(k+'.json')) for k in names}
                task=deepcopy(saved['phase0_config'])
                # Explicit board wiring is shared configuration, not a model
                # verification answer. Record the exact change on recovery.
                config_path=args.haven_root/'hdl'/name/'haven.json'
                config=json.loads(config_path.read_text())
                entry['board_config_sha256']=digest(config_path)
                entry['board_config_changes']={}
                for key in ('bfm_configs','bfm_overrides'):
                    if key in config and config[key] != task.get(key):
                        entry['board_config_changes'][key]={'before':task.get(key),'after':config[key]}
                        task[key]=deepcopy(config[key])
                bp=recover_blueprint(saved['phase2_blueprint'])
                if bp.get('module_name') != task['module_name']:
                    raise ValueError('saved architecture targets a different DUT')
                om=OutputManager(args.out/name,task['module_name'])
                state=dict(task=task,blueprint=bp,output_manager=om,config={})
                state=graph.finalize_protocol_flow(state,ProtocolFlows(**saved['phase2b_protocol_flows']))
                task,bp=prepare(state['task'],state['blueprint'],om.run_dir/'preflight',eda)
                rendered={key:TemplateEngine().render(key,bp) for key in ('interface','top')}
                for key,code in rendered.items():
                    (om.run_dir/(key+'.sv')).write_text(code)
                entry.update(status='environment_preflight_passed',directory=str(om.run_dir),
                    module=task['module_name'],agents=bp.get('topology',{}).get('agents',[]),
                    bfm_protocols=[b['protocol'] for b in bp.get('bfm_configs',[])])
            except Exception as error:
                entry.update(status='failed',error=str(error))
            entry['elapsed_seconds']=time.monotonic()-started
            save(args.out/'progress.json',report)
    report.update(status='passed' if all(e['status']=='environment_preflight_passed' for e in report['designs'].values()) else 'failed',
                  finished_utc=utc(),elapsed_seconds=time.monotonic()-began,
                  limitation='planning/environment check only; no new component generation or coverage experiment')
    save(args.out/'summary.json',report)
    save(args.out/'progress.json',report)
    print(json.dumps({n:{k:e.get(k) for k in ('status','error')} for n,e in report['designs'].items()}))
    return report['status']!='passed'


if __name__=='__main__': raise SystemExit(main())
