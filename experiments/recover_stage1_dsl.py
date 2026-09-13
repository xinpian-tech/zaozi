#!/usr/bin/env python3
"""Resume compilation using a logged model response after value-preserving JSON repair.

No model stimulus is authored here. Only complete, schema-valid responses from
the specified current Stage-1 log are eligible; preserve that log and prior costs.
"""
import argparse
import json
import logging
from pathlib import Path
import re
import sys
import time

from cycle_replay import digest
from haven_snapshot import snapshot
from run_records import save, utc


def recover_payload(text, module):
    from haven.dsl.schema import DSLSequenceSet
    from haven.utils.json_output import loads
    pattern = r'Structured output parsing error for DSLSequenceSet: Invalid json output: (\{.*?\})\s*\nFor troubleshooting,'
    errors = []
    for raw in reversed(re.findall(pattern,text,re.S)):
        try:
            parsed = DSLSequenceSet.model_validate(loads(raw))
            if parsed.module_name != module or not parsed.sequences:
                raise ValueError('wrong module or empty sequence set')
            return raw,parsed
        except ValueError as error:
            errors.append(str(error))
    raise ValueError('No recoverable complete model DSL response: '+'; '.join(errors))


def preserve_dispatch(before, replacement):
    tasks = []
    for name in ('env_dispatch_send','env_dispatch_wait'):
        matches = re.findall(r'\btask\s+'+name+r'\b.*?\bendtask\b',before,re.S)
        if len(matches)!=1: raise ValueError('ambiguous saved dispatch helper: '+name)
        tasks.append(matches[0])
    calls = re.findall(r'seq_item_port\.get_next_item\((\w+)\);',replacement)
    if len(calls)!=1 or replacement.count('seq_item_port.item_done();')!=1:
        raise ValueError('refreshed primary driver has ambiguous transaction loop')
    replacement = replacement.replace(f'seq_item_port.get_next_item({calls[0]});',
        f'seq_item_port.get_next_item({calls[0]});\nenv_dispatch_send({calls[0]});')
    replacement = replacement.replace('seq_item_port.item_done();','env_dispatch_wait();\nseq_item_port.item_done();')
    return replacement.replace('endclass','\n'.join(tasks)+'\nendclass')


def validate_baseline_checks(before, after):
    if before['module_name'] != after['module_name']:
        raise ValueError('baseline target changed')
    old = {s['name']:s for s in before['sequences']}
    new = {s['name']:s for s in after['sequences']}
    if len(new) != len(after['sequences']) or old.keys() != new.keys():
        raise ValueError('baseline sequence names or count changed')
    def checks(value):
        if isinstance(value,list): return sum(checks(v) for v in value)
        if not isinstance(value,dict): return 0
        return int(value.get('type') in ('poll','poll_until','check','assert','assert_expr')) + sum(checks(v) for v in value.values())
    for name in old:
        if checks(new[name]) < checks(old[name]):
            raise ValueError('baseline checks removed: '+name)


def compact_rtl_evidence(text):
    """Omit comments, retaining strings and line boundaries; RTL files stay intact."""
    token = r'"(?:\\.|[^"\\])*"|/\*.*?\*/|//[^\n]*'
    text = re.sub(token, lambda m: m[0] if m[0].startswith('"') else '\n'*m[0].count('\n')+' ', text, flags=re.S)
    return '\n'.join(line.strip() for line in text.splitlines() if line.strip())


def merge_model_dsl(before, response):
    changed = {s.name:s.model_dump() for s in response.sequences}
    if (response.module_name != before['module_name'] or len(changed) != len(response.sequences) or
            not set(changed) <= {s['name'] for s in before['sequences']}):
        raise ValueError('repair named unknown or duplicate baseline sequences')
    merged = {**before,'sequences':[changed.get(s['name'],s) for s in before['sequences']]}
    validate_baseline_checks(before,merged)
    return merged


def measured_log(path):
    text = path.read_text()
    try:
        value = json.loads(text)
    except ValueError:
        return text
    if isinstance(value, dict) and {'original_rtl','previous_model_dsl'} <= value.keys():
        raise ValueError('feedback-log must be a measured log, not a previous full prompt bundle')
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('stage1-run','haven-root','out','config'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--env-file',type=Path,help='credentials for model-enabled recovery only; unused with --reuse-dsl')
    parser.add_argument('--eda-shell',type=Path,default=Path(__file__).resolve().parent/'eda-shell',
                        help='shared EDA environment wrapper; no dependency on a host tcsh installation')
    parser.add_argument('--feedback-file',type=Path,help='ask the model to correct its baseline using its saved diagnosis')
    parser.add_argument('--feedback-log',type=Path,help='original measured simulation diagnostics')
    parser.add_argument('--extra-feedback-logs',type=Path,nargs='*',default=[],help='additional same-run diagnostic logs, not historical solutions')
    parser.add_argument('--refresh-bfms',action='store_true',help='render updated generic shared BFM templates into the new run, recording hashes')
    parser.add_argument('--reuse-dsl',action='store_true',help='reuse the saved model DSL verbatim; no new model request')
    parser.add_argument('--reuse-compiled-sequences',action='store_true',help='preserve saved post-repair model SystemVerilog too; requires --reuse-dsl')
    parser.add_argument('--repair-generated-drivers',action='store_true',help='joint model repair of baseline DSL and non-template drivers; shared setup only')
    parser.add_argument('--refresh-infrastructure',action='store_true',help='regenerate shared top and template drivers; no stimulus changes')
    parser.add_argument('--refresh-environment',action='store_true',help='offline CIRCT/JG clock/IO metadata refresh; requires verbatim compiled sequences and infrastructure refresh')
    parser.add_argument('--shared-item-source',type=Path,help='reuse an existing repaired shared item class, preserving source hashes; offline environment refresh only')
    parser.add_argument('--normalize-sequence-constraints',action='store_true',help='remove legacy item scenarios from sequence-owned payload fields; offline verbatim-sequence recovery only')
    parser.add_argument('--native-bfms',type=Path,help='explicit shared external BFM configurations to add; requires offline infrastructure/BFM refresh')
    args = parser.parse_args()
    if args.refresh_environment and not (args.reuse_dsl and args.reuse_compiled_sequences and args.refresh_infrastructure and args.refresh_bfms):
        parser.error('--refresh-environment requires --reuse-dsl --reuse-compiled-sequences --refresh-infrastructure --refresh-bfms')
    if args.shared_item_source and not args.refresh_environment:
        parser.error('--shared-item-source requires --refresh-environment')
    if args.normalize_sequence_constraints and not (args.reuse_dsl and args.reuse_compiled_sequences):
        parser.error('--normalize-sequence-constraints requires --reuse-dsl --reuse-compiled-sequences')
    if args.native_bfms and not (args.reuse_dsl and args.refresh_infrastructure and args.refresh_bfms):
        parser.error('--native-bfms requires --reuse-dsl --refresh-infrastructure --refresh-bfms')
    if args.reuse_compiled_sequences and not args.reuse_dsl:
        parser.error('--reuse-compiled-sequences requires --reuse-dsl')
    if args.reuse_dsl and args.feedback_file:
        parser.error('--reuse-dsl and --feedback-file are mutually exclusive')
    if args.repair_generated_drivers and not args.feedback_file:
        parser.error('joint driver repair requires measured failure feedback')
    if not args.reuse_dsl and args.env_file is None:
        parser.error('--env-file is required unless --reuse-dsl disables model calls')
    stage,args.out = args.stage1_run.resolve(),args.out.resolve()
    if not (stage/'ir/phase5_components').is_dir():
        raise ValueError('wait for the original compile worker to finish and save its components')
    args.out.mkdir(parents=True,exist_ok=False)
    implementation = snapshot(args.haven_root,args.out/'implementation/haven')
    sys.path.insert(0,str(implementation/'src'))
    if not args.reuse_dsl:
        from sequence_experiment import load_env_file
        load_env_file(args.env_file)
    from haven.main import _load_state_from_ir, _save_token_summary
    from haven.utils.llm_client import reset_token_tracker
    from haven.utils.output_manager import OutputManager
    import haven.graph.task_graph as graph
    logging.basicConfig(level=logging.INFO)
    task = json.loads((stage/'ir/phase0_config.json').read_text())
    log_path = stage.parents[1]/'stage1.log'
    raw, parsed, feedback = None,None,None
    if args.feedback_file:
        if not args.feedback_log: raise ValueError('model baseline repair requires measured simulation diagnostics')
        feedback = json.dumps({'diagnostic_context':args.feedback_file.read_text(),
            'measured_simulation':measured_log(args.feedback_log),
            'additional_diagnostics':{str(p):measured_log(p) for p in args.extra_feedback_logs},
            'previous_model_dsl':json.loads((stage/'ir/phase4b_dsl_sequences.json').read_text()),
            'authoritative_specification':(Path(task['root'])/task['spec']).read_text(),
            'original_rtl':{str(p):(Path(task['root'])/p).read_text() for p in task['rtl_files']},
            'frozen_drivers':{p.name:p.read_text() for p in (stage/'final').glob('*driver.sv')}},ensure_ascii=False)
    elif args.reuse_dsl:
        from haven.dsl.schema import DSLSequenceSet
        parsed = DSLSequenceSet.model_validate(json.loads((stage/'ir/phase4b_dsl_sequences.json').read_text()))
        if parsed.module_name != task['module_name'] or not parsed.sequences:
            raise ValueError('saved model DSL has wrong module or no sequences')
    else:
        raw, parsed = recover_payload(log_path.read_text(),task['module_name'])
    om = OutputManager(args.out/'stage1',task['module_name'])
    if feedback is not None:
        # Documentation and diagnostic paths can change after launch. Preserve
        # the exact evidence supplied to this call, not just its source hashes.
        save(om.run_dir/'feedback-input.json',json.loads(feedback))
    for name in ('phase0_config.json','phase1_structured_spec.json','phase2b_blueprint.json','phase2b_protocol_flows.json'):
        save(om.ir_dir/name,json.loads((stage/'ir'/name).read_text()))
    if raw is not None: (om.run_dir/'recovered-model-response.txt').write_text(raw)
    save(om.run_dir/'resumed-from.json',{
        'run':str(stage),'prior_cost_record':str(stage.parents[1]/'stage1-costs.json'),
        'source_log':str(log_path) if log_path.is_file() else None,
        'source_log_sha256':digest(log_path) if log_path.is_file() else None,
        'feedback_sources_sha256':{str(p.resolve()):digest(p) for p in (args.feedback_file,args.feedback_log,*args.extra_feedback_logs) if p},
        'source_components_sha256':{str(p):digest(p) for p in (stage/'ir/phase5_components').glob('*') if p.is_file()},
        'policy':('model corrects shared baseline from source specification and its own measured-failure diagnosis; no local stimulus edits'
                  if feedback else 'reuse saved model DSL verbatim; no new authored stimulus' if args.reuse_dsl
                  else 'reuse same-run model response; normalize bare hex integer representation only; no new authored stimulus'),
        'saved_model_dsl_sha256':digest(stage/'ir/phase4b_dsl_sequences.json') if args.reuse_dsl else None})
    tracker = reset_token_tracker()
    began = time.monotonic()
    record = {'status':'failed','started_utc':utc(),'resumed_from':str(stage),'haven_snapshot':str(implementation)}
    if (stage/'manual-diagnostic.json').is_file():
        diagnostic = json.loads((stage/'manual-diagnostic.json').read_text())
        if diagnostic.get('diagnostic_only') is not True:
            raise ValueError('invalid manual baseline provenance')
        record['diagnostic_only'] = True
        record['formal_experiment'] = False
        save(om.run_dir/'manual-diagnostic.json',diagnostic)
    from contextlib import ExitStack
    model_guard = ExitStack()
    if args.reuse_dsl:
        from offline_validation import no_model_calls
        model_guard.enter_context(no_model_calls())
        record['model_requests_allowed'] = False
    try:
        state = _load_state_from_ir({'config':{**json.loads(args.config.read_text()),'task':task},
                                    'task':task,'output_manager':om},stage/'ir',6)
        state['config'].setdefault('eda_env', {})['shell'] = str(args.eda_shell.resolve())
        if args.reuse_dsl:
            state['config'].setdefault('simulation', {})['model_repairs'] = False
        if args.refresh_environment:
            from environment_preflight import prepare
            task, state['blueprint'] = prepare(task,state['blueprint'],om.run_dir/'environment',args.eda_shell)
            from haven.utils.transaction_contract import apply_transaction_contract
            state['blueprint'] = apply_transaction_contract(state['blueprint'])
            state['task'] = task
            state['config']['task'] = task
            state['protocol_flows'] = state['blueprint']['protocol_flows']
            for name,value in [('phase0_config',task),('phase2b_blueprint',state['blueprint']),
                               ('phase2b_protocol_flows',state['protocol_flows'])]:
                save(om.ir_dir/(name+'.json'),value)
            record['environment_refresh'] = 'deterministic CIRCT/JG IO and clock discovery; no new model requests or stimulus edits'
        if args.shared_item_source:
            from haven.utils.transaction_contract import validate_item_contract
            code=args.shared_item_source.read_text()
            if not re.search(r'\bclass\s+'+re.escape(task['module_name'])+r'_seq_item\s+extends\s+uvm_sequence_item\s*;',code):
                raise ValueError('saved shared item class must match the design')
            errors=validate_item_contract(code,state['blueprint']['data_contracts']['seq_item_fields'])
            if errors: raise ValueError('saved shared item contract: '+'; '.join(errors))
            record['shared_item_source']={'path':str(args.shared_item_source.resolve()),'sha256':digest(args.shared_item_source),
                'previous_sha256':digest(stage/'final'/(task['module_name']+'_seq_item.sv'))}
            state['components']['seq_item']=code
        if args.normalize_sequence_constraints:
            from item_contract import normalize
            from run_records import fingerprint
            before=state['components']['seq_item']
            after,removed=normalize(before,state['blueprint'])
            state['components']['seq_item']=after
            record['sequence_constraint_normalization']=dict(before=fingerprint(before),after=fingerprint(after),
                removed=removed,policy='shared input payload domain; preserve field types and all compiled baseline sequences')
        if args.native_bfms:
            from haven.utils.environment_contract import normalize_environment
            from haven.utils.template_engine import TemplateEngine
            additions = json.loads(args.native_bfms.read_text())
            existing = {b['protocol'] for b in state['blueprint'].get('bfm_configs',[])}
            if not isinstance(additions,list) or any(b['protocol'] in existing for b in additions):
                raise ValueError('native BFM additions must not overwrite installed models')
            old_agents = {a['name']:a['mode'] for a in state['blueprint']['topology']['agents']}
            state['blueprint'].setdefault('bfm_configs',[]).extend(additions)
            state['blueprint'] = normalize_environment(state['blueprint'],task)
            state['protocol_flows'] = state['blueprint']['protocol_flows']
            for agent in state['blueprint']['topology']['agents']:
                if agent['mode'] != old_agents[agent['name']]:
                    if agent['mode'] != 'passive':
                        raise ValueError('native BFM migration may only retire fully replaced drivers')
                    prefix = agent['name']+'__'
                    state['components'].pop(prefix+'driver',None)
                    state['components'].pop(prefix+'sequencer',None)
                    state['components'][prefix+'agent'] = TemplateEngine().render('agent',state['blueprint'],
                        agent_name=agent['name'],agent_mode='passive')
            state['components']['env'] = TemplateEngine().render('env',state['blueprint'])
            save(om.ir_dir/'phase2b_blueprint.json',state['blueprint'])
            save(om.ir_dir/'phase2b_protocol_flows.json',state['protocol_flows'])
            record['native_bfm_additions'] = {'source_sha256':digest(args.native_bfms),'configs':additions,
                'scope':'shared Stage-1 for both arms; original artifacts preserved'}
        if args.refresh_infrastructure:
            from haven.utils.template_engine import TemplateEngine
            from haven.utils.protocol_driver_renderer import ProtocolDriverRenderer
            from haven.graph.compile_utils import build_protected_set, get_driver_keys
            from run_records import fingerprint
            before = {k:fingerprint(v) for k,v in state['components'].items()}
            state['components']['top'] = TemplateEngine().render('top',state['blueprint'])
            state['components']['interface'] = TemplateEngine().render('interface',state['blueprint'])
            protected = build_protected_set(state['blueprint'],state['components'])
            for key in get_driver_keys(state['components'],state['blueprint']):
                if key in protected:
                    agent = key.removesuffix('__driver') if key != 'driver' else None
                    replacement = ProtocolDriverRenderer().render_driver(state['blueprint'],agent_name=agent)
                    dispatch = state['blueprint'].get('sequence_dispatch')
                    if dispatch and key == dispatch['primary']+'__driver':
                        replacement = preserve_dispatch(state['components'][key],replacement)
                    state['components'][key] = replacement
            record['infrastructure_refresh'] = {k:{'before':before.get(k),'after':fingerprint(v)} for k,v in state['components'].items() if before.get(k)!=fingerprint(v)}
        if args.refresh_bfms:
            from haven.dsl.schema import BFMConfig
            from haven.utils.bfm_renderer import BFMRenderer
            from run_records import fingerprint
            # The native loader already renders today's templates. Provenance
            # must compare against the immutable source run, not that rendering.
            original_bfms = {p.stem:p.read_text() for p in (stage/'final').glob('bfm_*.sv')}
            state['bfm_components'] = BFMRenderer().render_all([
                BFMConfig(**b) for b in state['blueprint'].get('bfm_configs',[])])
            record['shared_bfm_refresh'] = {
                'before':{k:fingerprint(v) for k,v in original_bfms.items()},
                'after':{k:fingerprint(v) for k,v in state['bfm_components'].items()},
                'scope':'new shared setup for both arms; original artifacts unchanged'}
        if args.repair_generated_drivers:
            from haven.graph.compile_utils import build_protected_set, get_driver_keys
            from haven.dsl.schema import DSLSequenceSet
            from haven.utils.llm_client import LLMClient
            from pydantic import create_model
            from repair_shared_driver import validate_replacement
            protected = build_protected_set(state['blueprint'],state['components'])
            keys = [key for key in get_driver_keys(state['components'],state['blueprint']) if key not in protected]
            if not keys:
                raise ValueError('no model-generated drivers eligible for shared repair')
            schema = create_model('SharedBaselineRepair',drivers=(dict[str,str],...),dsl=(DSLSequenceSet,...))
            prompt = ('Repair this shared Stage-1 baseline from raw RTL, specification and measured failure. '
                'Return only changed DSL sequence objects (unchanged sequences are kept verbatim) and complete replacement classes for exactly the listed generated drivers. '
                'Preserve all sequence names, verification intents and meaningful checks. Correct mistaken register semantics or '
                'transaction implementation, but do not delete checks, replace them with tautologies, suppress reporting, fabricate '
                'observed outputs or force internal RTL. Template drivers, BFMs, topology, dispatch queues and transaction API are frozen. '
                'Both experimental arms will use this same repaired setup only if it passes simulation. '
                'Each sequence runs independently after reset; no previous sequence state is available.\n'
                +json.dumps({'failure':{k:({p:compact_rtl_evidence(code) for p,code in v.items()} if k=='original_rtl' else v)
                                       for k,v in json.loads(feedback).items() if k!='frozen_drivers'},
                    'rtl_evidence_policy':'all original RTL code; comments and blank lines omitted in prompt only',
                    'environment':{k:state['blueprint'].get(k) for k in ('topology','environment_contract','sequence_dispatch','clock','reset')},
                    'bus_mapping':json.loads((stage/'ir/phase2b_protocol_flows.json').read_text()).get('bus_field_mapping'),
                    'frozen_drivers':{k:v for k,v in state['components'].items() if k.endswith('driver') and k not in keys},
                    'seq_item':state['components']['seq_item'],
                    'repairable_drivers':{k:state['components'][k] for k in keys}},ensure_ascii=False))
            (om.run_dir/'joint-repair-prompt.txt').write_text(prompt)
            repair = LLMClient.for_coding(state['config']).call_structured(prompt,schema)
            save(om.run_dir/'joint-repair-response.json',repair.model_dump())
            if set(repair.drivers) != set(keys):
                raise ValueError('joint repair changed the allowed driver set')
            original_dsl = json.loads((stage/'ir/phase4b_dsl_sequences.json').read_text())
            merged = merge_model_dsl(original_dsl,repair.dsl)
            for key,code in repair.drivers.items():
                validate_replacement(state['components'][key],code,key,state['blueprint'])
                state['components'][key] = code
            parsed,feedback = DSLSequenceSet.model_validate(merged),None
            record['joint_repair_drivers'] = keys
        if feedback is not None:
            from haven.dsl.schema import DSLSequenceSet
            from haven.utils.llm_client import LLMClient
            evidence = json.loads(feedback)
            evidence['original_rtl'] = {p:compact_rtl_evidence(code) for p,code in evidence['original_rtl'].items()}
            prompt = ('Repair only the saved baseline DSL using the original RTL and measured failure. '
                'Return only changed sequence objects; others are retained verbatim. Keep sequence names, intents and meaningful checks. '
                'Do not delete checks, use tautologies, suppress errors or change the transaction API. '
                'All drivers, BFMs, topology and RTL are frozen. Each sequence runs independently after reset. '
                'All original RTL code is included, with comments and blank lines omitted from this prompt only.\n'+
                json.dumps({'evidence':evidence,'seq_item':state['components']['seq_item'],
                    'bus_mapping':json.loads((stage/'ir/phase2b_protocol_flows.json').read_text()).get('bus_field_mapping')},ensure_ascii=False))
            (om.run_dir/'dsl-repair-prompt.txt').write_text(prompt)
            response = LLMClient.for_coding(state['config']).call_structured(prompt,DSLSequenceSet)
            save(om.run_dir/'dsl-repair-response.json',response.model_dump())
            parsed = DSLSequenceSet.model_validate(merge_model_dsl(evidence['previous_model_dsl'],response))
        class SavedDSL:
            def __init__(self, **kwargs): pass
            def run(self, **kwargs): return parsed.model_copy(deep=True)
        graph.DSLGenerator = SavedDSL
        if args.reuse_compiled_sequences:
            if not state.get('sequences'):
                raise ValueError('saved run has no compiled sequences to preserve')
            from run_records import fingerprint
            record['reused_compiled_sequences_sha256'] = fingerprint(state['sequences'])
            om.save_sequences('phase4_sequences',state['sequences'])
            save(om.ir_dir/'phase4b_dsl_sequences.json',parsed.model_dump())
        else:
            state = graph.node_sequence_gen(state)
        state = graph.node_compile_check(state)
        om.save_final(task['module_name'],state['components'],state['sequences'],bfm_components=state.get('bfm_components'))
        if not state.get('compile_passed') or not state['sequences']:
            raise ValueError('recovered baseline did not pass shared compilation')
        record.update(status='stage1_ready',stage1=str(om.run_dir),sequence_count=len(state['sequences']),
                      paired_status='pending_adapter_validation')
    except BaseException as error:
        record.update(error=str(error),error_type=type(error).__name__)
        raise
    finally:
        model_guard.close()
        _save_token_summary(om,tracker)
        record.update(finished_utc=utc(),elapsed_seconds=time.monotonic()-began,native_token_tracker=tracker.summary())
        save(args.out/'stage1-costs.json',record)


if __name__ == '__main__':
    main()
