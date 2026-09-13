#!/usr/bin/env python3
"""Bounded model repair of one generated shared driver, with frozen tests/RTL.

The repaired bundle is eligible for both arms only after the original baseline
passes. Never change assertions, sequences, BFMs, templates or coverage targets.
"""
import argparse
from copy import deepcopy
import json
from pathlib import Path
import re
import time
from types import SimpleNamespace

from coverage_flow import load_bundle, HavenSimulation
from cycle_replay import digest
from run_records import save, utc, totals, fingerprint, Records
from sequence_experiment import load_env_file, request_model, strip_fence


def validate_replacement(before, code, key, blueprint):
    from haven.utils.environment_contract import validate_driver_ownership
    agents = blueprint.get('topology',{}).get('agents',[])
    name = key.removesuffix('__driver') if key != 'driver' else agents[0]['name']
    errors = validate_driver_ownership(code,name,blueprint)
    original_class = re.findall(r'\bclass\s+(\w+)\s+extends',before)
    if re.findall(r'\bclass\s+(\w+)\s+extends',code) != original_class:
        errors.append('driver class/API changed')
    # Dispatch queues are shared infrastructure, not a model repair target.
    for queue,method in re.findall(r'\b(\w+_env_dispatch_\w+_(?:requests|done))\.(get|put)\(',before):
        if code.count(queue+'.'+method+'(') != 1:
            errors.append('shared dispatch get/ack changed')
    clean = re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"',' ',code,flags=re.S)
    if re.search(r'\$root|\$finish|\bforce\b|\brelease\b|\buvm_hdl_|\bset_report_|\buvm_report_catcher|\buvm_report_server',clean):
        errors.append('driver may not alter DUT internals or simulation/reporting controls')
    if errors: raise ValueError('; '.join(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('bundle','haven-root','env-file','diagnostic-log','out'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--eda-config',type=Path,default=Path(__file__).parent/'designs/haven_eda.json')
    parser.add_argument('--eda-shell',type=Path,default=Path(__file__).parent/'eda-shell')
    parser.add_argument('--attempts',type=int,default=3)
    parser.add_argument('--driver',help='one exact non-template driver key, when several are present')
    args = parser.parse_args()
    original,design,replay = load_bundle(args.bundle,args.haven_root)
    from haven.graph.compile_utils import build_protected_set, get_driver_keys
    protected = build_protected_set(original['blueprint'],original['components'])
    keys = [k for k in get_driver_keys(original['components'],original['blueprint']) if k not in protected]
    if args.driver:
        if args.driver not in keys: raise ValueError('requested driver is not eligible for model repair')
        keys = [args.driver]
    if len(keys) != 1:
        raise ValueError('automatic repair requires one unambiguous non-template driver')
    key = keys[0]
    args.out = args.out.resolve()
    args.out.mkdir(parents=True,exist_ok=False)
    load_env_file(args.env_file)
    config = json.loads(args.eda_config.read_text())
    config['eda_env'] = {'shell':str(args.eda_shell.resolve())}
    options = SimpleNamespace(model='deepseek-v4-flash-vision-exp',temperature=0.3,timeout=600,request_retries=3)
    began = time.monotonic()
    record = {'status':'running','started_utc':utc(),'source_bundle':str(args.bundle.resolve()),
              'source_bundle_sha256':digest(args.bundle),'driver':key,'attempts':[],
              'policy':'shared setup only; original RTL/BFM/sequence/check/coverage artifacts frozen'}
    save(args.out/'progress.json',record)
    diagnostic = args.diagnostic_log.read_text()
    code = original['components'][key]
    try:
        for number in range(1,args.attempts+1):
            directory = args.out/f'attempt-{number}'
            directory.mkdir()
            context = {'specification':design.context,'environment':{k:original['blueprint'].get(k) for k in
                       ('topology','environment_contract','sequence_dispatch','clock_schedule','clock','reset')},
                       'interface':original['components']['interface'],
                       'seq_item':original['components']['seq_item'],
                       'other_drivers':{k:v for k,v in original['components'].items() if k.endswith('driver') and k!=key},
                       'baseline_dsl':original['initial_dsl']}
            prompt = ('Repair the generated shared UVM driver using the measured failure below. '
                      'Return only its complete SystemVerilog class. All other components, RTL, BFMs, '
                      'clock schedule, baseline sequences, checks and thresholds are frozen. '
                      'Preserve the dispatch queues and the transaction API. Drive only this agent\'s '
                      'owned pins. Do not suppress reports, bypass checks, force DUT internals, fabricate '
                      'observed outputs or acknowledge transactions without implementing their behavior. '
                      'Do not change or generate test sequences. If this cannot be repaired in this driver, '
                      'explain why instead of returning a fake passing driver.\n'
                      'Each baseline sequence runs in its own fresh simulation; do not assume previous sequence state.\n'
                      +json.dumps(context,ensure_ascii=False)+'\nCurrent driver:\n'+code+'\nMeasured diagnostics:\n'+diagnostic)
            (directory/'prompt.txt').write_text(prompt)
            raw,info = request_model(options,prompt,directory,Records(directory))
            (directory/'response.txt').write_text(raw)
            save(directory/'provider.json',info)
            if not re.search(r'\bclass\s+\w+\s+extends',raw):
                record.update(status='model_reports_driver_scope_insufficient',
                              diagnosis=str(directory/'response.txt'))
                break
            attempt = {'attempt':number,'status':'failed'}
            record['attempts'].append(attempt)
            try:
                replacement = strip_fence(raw)
                validate_replacement(original['components'][key],replacement,key,original['blueprint'])
                code = replacement
                bundle = deepcopy(original)
                bundle['components'][key] = code
                source = directory/(key+'.sv')
                source.write_text(code)
                bundle['sources'][str(source)] = digest(source)
                bundle['repairs'].append('model-generated shared driver runtime repair; original baseline/checks frozen')
                bundle.pop('fingerprint')
                bundle['fingerprint'] = fingerprint(bundle)
                save(directory/'bundle.json',bundle)
                from isolated_replay import IsolatedSimulation
                result = IsolatedSimulation(HavenSimulation(bundle,design,config,20260906),directory/'replay-cache')(
                    directory/'baseline',bundle['sequences'],[])
                attempt.update(status='passed',coverage=result['percent'],bundle=str(directory/'bundle.json'))
                record.update(status='runtime_ready',bundle=str(directory/'bundle.json'))
                break
            except ValueError as error:
                attempt['error'] = str(error)
                diagnostics = [p.read_text() for p in (directory/'baseline').glob('*.log')]
                diagnostic = str(error)+'\n'+json.dumps(getattr(error,'diagnostics',{}))+'\n'+'\n'.join(diagnostics)
                save(directory/'error.json',{'error':str(error)})
            save(args.out/'progress.json',record)
        else:
            record['status'] = 'repair_budget_exhausted'
    except BaseException as error:
        record.update(status='failed',error=str(error))
        raise
    finally:
        record.update(finished_utc=utc(),elapsed_seconds=time.monotonic()-began,costs=totals(args.out))
        save(args.out/'progress.json',record)
        save(args.out/'summary.json',record)


if __name__ == '__main__':
    main()
