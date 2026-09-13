"""Build an explicitly human-authored diagnostic Stage-1 with native templates.

Inputs are an explicit task/IO protocol mapping and baseline DSL, never guessed
benchmark stimulus. CIRCT/JG determine IO/clock roles; VCS must really compile.
This entry point cannot call a model and marks its products non-benchmark.
"""
import argparse
import json
from pathlib import Path
import sys
import time

from run_records import save, utc
from cycle_replay import digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('input', 'haven-root', 'out'):
        parser.add_argument('--'+key, type=Path, required=True)
    args = parser.parse_args()
    args.out = args.out.resolve()
    args.out.mkdir(parents=True, exist_ok=False)
    from haven_snapshot import snapshot
    haven = snapshot(args.haven_root, args.out/'implementation/haven')
    sys.path.insert(0, str(haven/'src'))
    from offline_validation import no_model_calls
    from haven.utils.output_manager import OutputManager
    from haven.utils.template_engine import TemplateEngine
    from haven.utils.protocol_driver_renderer import ProtocolDriverRenderer
    from haven.utils.transaction_contract import apply_transaction_contract
    from haven.utils.bfm_renderer import BFMRenderer
    from haven.dsl.schema import DSLSequenceSet, BusFieldMapping, BFMConfig
    from haven.dsl.codegen import DSLCodegen
    from haven.graph.rendering import render_templates
    from haven.graph.task_graph import node_compile_check
    from environment_preflight import prepare
    raw = json.loads(args.input.read_text())
    task, bp = raw['task'], raw['blueprint']
    module = task['module_name']
    om = OutputManager(args.out/'stage1', module)
    record = dict(status='running', started_utc=utc(), diagnostic_only=True,
                  formal_experiment=False, remote_llm_requests=0, author_token_usage=None,
                  input_sha256=digest(args.input), stage1=str(om.run_dir))
    save(om.run_dir/'manual-diagnostic.json', record)
    began = time.monotonic()
    try:
        with no_model_calls():
            task, bp = prepare(task, bp, om.run_dir/'environment', Path(__file__).parent.resolve()/'eda-shell')
            fields = [{**p, 'type':'logic', 'is_rand':direction=='input', 'direction':direction}
                      for direction, key in [('input','inputs'),('output','outputs')]
                      for p in bp['io_specification'][key]]
            bp['data_contracts'] = {'seq_item_fields':fields}
            bp = apply_transaction_contract(bp)
            fields = bp['data_contracts']['seq_item_fields']
            components = {}
            te = TemplateEngine()
            for key in ('interface','sequencer','agent','scoreboard','env','top'):
                components[key] = te.render(key,bp)
            components['seq_item'] = '\n'.join([
                f'class {module}_seq_item extends uvm_sequence_item;',
                f'`uvm_object_utils({module}_seq_item)',
                *[f'{"rand " if f.get("is_rand") else ""}logic [{f["width"]-1}:0] {f["name"]};' for f in fields],
                f'function new(string name="{module}_seq_item"); super.new(name); endfunction', 'endclass'])
            renderer = ProtocolDriverRenderer()
            components['driver'] = renderer.render_driver(bp)
            components['monitor'] = renderer.render_monitor(bp)
            # Code coverage and original LTL are the diagnostic oracles. This
            # observer records transactions, not a fabricated reference model.
            components['subscriber'] = '\n'.join([
                f'class {module}_subscriber extends uvm_subscriber #({module}_seq_item);',
                f'`uvm_component_utils({module}_subscriber)', 'int observed = 0;',
                f'function new(string name="{module}_subscriber", uvm_component parent=null); super.new(name,parent); endfunction',
                f'function void write({module}_seq_item t); observed++; endfunction', 'endclass'])
            dsl = DSLSequenceSet.model_validate(raw['dsl'])
            if dsl.module_name != module or not dsl.sequences:
                raise ValueError('nonempty diagnostic DSL must match task')
            sequences = DSLCodegen().generate(dsl, BusFieldMapping(**bp['protocol_flows']['bus_field_mapping']),
                fields, seq_item_code=components['seq_item'], bfm_configs=bp.get('bfm_configs',[]))
            config = json.loads((Path(__file__).parent/'designs/haven_eda.json').read_text())
            config['eda_env'] = {'shell':str(Path(__file__).parent.resolve()/'eda-shell')}
            config.setdefault('simulation',{}).update(model_repairs=False, sim_retry_limit=1)
            state = dict(task=task, blueprint=bp, components=components, sequences=sequences,
                         structured_spec=raw.get('structured_spec',{}), config=config, output_manager=om,
                         bfm_components=BFMRenderer().render_all([BFMConfig(**b) for b in bp.get('bfm_configs',[])]))
            render_templates(state)
            for name, value in [('phase0_config',task),('phase1_structured_spec',state['structured_spec']),
                                ('phase2b_blueprint',bp),('phase2b_protocol_flows',bp['protocol_flows']),
                                ('phase4b_dsl_sequences',dsl.model_dump())]:
                save(om.ir_dir/(name+'.json'),value)
            state = node_compile_check(state)
            om.save_final(module,state['components'],sequences,bfm_components=state['bfm_components'])
            if not state.get('compile_passed'):
                raise ValueError('real Stage-1 compilation failed; see compile_check')
            record['status']='stage1_ready'
    except Exception as error:
        record.update(status='failed', error=str(error))
        raise
    finally:
        record.update(finished_utc=utc(),elapsed_seconds=time.monotonic()-began)
        save(om.run_dir/'manual-diagnostic.json',record)
        save(args.out/'summary.json',record)
    print(json.dumps(record))


if __name__ == '__main__': main()
