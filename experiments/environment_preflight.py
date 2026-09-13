"""Deterministic shared Stage-1 environment preflight; no model requests."""
from copy import deepcopy
import json
from pathlib import Path

from rtl_environment import discover, import_ports, parse_roles
from run_records import save


def prepare(task, blueprint, directory, eda_shell):
    from haven.utils.environment_contract import normalize_environment
    directory = Path(directory)
    directory.mkdir(parents=True,exist_ok=False)
    task, blueprint = deepcopy(task),deepcopy(blueprint)
    ports = import_ports(task,directory/'io')
    if any(p['direction']=='inout' for p in ports):
        from bidirectional_wrapper import adapt
        task, blueprint = adapt(task,blueprint,ports,directory/'resolved-io')
        ports = import_ports(task,directory/'resolved-import')
    report = discover(task,directory/'roles',eda_shell)
    roles = parse_roles(report,[p for p in ports if p['direction']=='input'],
                        blueprint['clock']['port'],blueprint['reset'])
    save(directory/'classified-roles.json',roles)
    exclude = {blueprint['clock']['port'],blueprint['reset']['name']}
    io = blueprint['io_specification']
    old = {p['name']:p for key in ('inputs','outputs','inouts') for p in io.get(key,[]) or []}
    for direction,key in [('input','inputs'),('output','outputs')]:
        io[key] = [{**old.get(p['name'],{}),**p} for p in ports if p['direction']==direction and p['name'] not in exclude]
    io['inouts'] = []
    io['clocks'] = [{'name':name} for name in roles['clocks']]
    explicit = {r['signal']:r for r in task.get('extra_resets',[]) or []}
    for reset in roles['extra_resets']:
        if reset['signal'] in explicit and explicit[reset['signal']]['level'] != reset['level']:
            raise ValueError('configured secondary reset polarity contradicts elaborated RTL')
        explicit.setdefault(reset['signal'],reset)
    task['extra_resets'] = list(explicit.values())
    task['clock_schedule'] = [{'port':blueprint['clock']['port'],'period_ps':10000}] + [
        {'port':name,'period_ps':6000+index*4000}
        for index,name in enumerate(n for n in roles['clocks'] if n!=blueprint['clock']['port'])]
    blueprint = normalize_environment(blueprint,task)
    # MII's two clock outputs come from the SAME divider, not two unrelated
    # free oscillators. This is template metadata, not benchmark stimulus.
    periods = {c['port']:c['period_ps'] for c in task['clock_schedule']}
    for bfm in blueprint.get('bfm_configs',[]):
        if bfm['protocol'] != 'mii_phy': continue
        divide = bfm.get('params',{}).get('clk_div',2)
        if type(divide) is not int or divide < 1: raise ValueError('invalid MII clock divider')
        outputs = {bfm['signals'].get(k) for k in ('rx_clk','tx_clk')}
        independent = sorted(set(periods)-outputs-{blueprint['clock']['port']})
        source = independent[0] if bfm.get('params',{}).get('clock_domain')=='secondary' and independent else blueprint['clock']['port']
        for name in outputs:
            if name not in periods: raise ValueError('MII clock is absent from elaborated clock roles')
            periods[name] = periods[source]*divide*2
    task['clock_schedule'] = [{'port':c['port'],'period_ps':periods[c['port']]} for c in task['clock_schedule']]
    blueprint['clock_schedule'] = deepcopy(task['clock_schedule'])
    blueprint['rtl_environment_evidence'] = report
    save(directory/'task.json',task)
    save(directory/'blueprint.json',blueprint)
    return task,blueprint


def write_replay_manifest(task, blueprint, directory, context='', *, boundary=None):
    """Derive run IO/environment metadata, never verification intents or UT code."""
    directory = Path(directory)
    directory.mkdir(parents=True,exist_ok=False)
    clocks = {c['port'] for c in blueprint['clock_schedule']}
    primary,reset = blueprint['clock']['port'],blueprint['reset']['name']
    ports = [{'name':primary,'direction':'input','width':1,'kind':'clock'},
             {'name':reset,'direction':'input','width':1,'kind':'bool'}]
    for direction,key in [('input','inputs'),('output','outputs')]:
        for p in blueprint['io_specification'][key]:
            ports.append({'name':p['name'],'direction':direction,'width':p['width'],
                          'kind':'clock' if p['name'] in clocks else 'bool' if p['width']==1 else 'bits'})
    from environment_contract import passive_open_drain
    env = {'version':'shared-event-environment-v1','clocks':blueprint['clock_schedule'],
           'static':blueprint.get('static_signals') or {},
           'extra_resets':[{'port':r['signal'],'active_low':r['level']=='low'} for r in blueprint.get('extra_resets',[])],
           'open_drain':[{k:p[k] for k in ('input','output','enable_n')} for p in blueprint.get('open_drain_connections',[])],
           'feedback':blueprint.get('feedback_connections',[])}
    if passive_open_drain(blueprint):
        env['passive_open_drain'] = passive_open_drain(blueprint)
    if boundary is not None:
        from environment_contract import INDEPENDENT, independent_environment
        if boundary != INDEPENDENT:
            raise ValueError('unsupported DUT boundary')
        env = independent_environment(env)
    sources = [str((Path(task['root'])/p).resolve()) for p in task['rtl_files']]
    design = {'version':1,'top':task['module_name'],'sources':sources,'ports':ports,'clock':primary,
              'include_dirs':sorted({str(Path(p).parent) for p in sources}),
              'reset':{'port':reset,'active_low':blueprint['reset']['level']=='low'},
              'sequence':{'name':'formal_sequence','item_type':task['module_name']+'_seq_item'},'context':context}
    idle = {p['name']:0 for p in ports if p['direction']=='input' and p['name'] not in clocks|{reset}}
    idle.update(env['static'])
    for r in env['extra_resets']: idle[r['port']] = int(r['active_low'])
    # Paired mode uses saved native baseline sequences, not this standalone
    # reset-only metadata. No synthetic request transaction is added.
    replay = {'version':1,'contract':'cycle-replay-v1','design':'design.json','reset_cycles':10,
              'drain_cycles':1,'idle':idle,
              'baseline':{'mode':'reset-only','idle_cycles':1},'environment':env}
    save(directory/'design.json',design)
    save(directory/'replay.json',replay)
    from cycle_replay import load_config
    from environment_contract import verify_shared_environment
    checked,_ = load_config(directory/'replay.json')
    verify_shared_environment(checked,env,blueprint)
    return directory/'replay.json'


def install_stage1_preflight(eda_shell):
    """Install before HAVEN constructs its graph; retain the native component generator."""
    import haven.graph.task_graph as graph
    original = graph.node_testbench_gen
    finalize = graph.finalize_protocol_flow
    def checked_flow(state, flows):
        # Preserve the paid model result before any CIRCT/environment failure.
        state['output_manager'].save_ir_json('phase2b','protocol_flows',flows.model_dump())
        # Width-dependent BFM checks run in Phase 2B, before Phase 3. Never
        # validate them against the native parser's guessed macro widths.
        ports = import_ports(state['task'],state['output_manager'].run_dir/'protocol-io')
        bp = deepcopy(state['blueprint'])
        excluded = {bp['clock']['port'],bp['reset']['name']}
        io = bp['io_specification']
        old = {p['name']:p for key in ('inputs','outputs','inouts') for p in io.get(key,[]) or []}
        for direction,key in [('input','inputs'),('output','outputs')]:
            io[key] = [{**old.get(p['name'],{}),**p} for p in ports
                       if p['direction']==direction and p['name'] not in excluded]
        io['inouts'] = [p for p in ports if p['direction']=='inout']
        widths = {p['name']:p['width'] for p in ports}
        for field in bp.get('data_contracts',{}).get('seq_item_fields',[]):
            if field['name'] in widths: field['width'] = widths[field['name']]
        state['blueprint'] = bp
        return finalize(state,flows)
    graph.finalize_protocol_flow = checked_flow
    def checked(state):
        directory = state['output_manager'].run_dir/'environment-preflight'
        task,bp = prepare(state['task'],state['blueprint'],directory,eda_shell)
        state['task'],state['blueprint'] = task,bp
        if bp.get('protocol_flows'):
            state['protocol_flows'] = deepcopy(bp['protocol_flows'])
            state['output_manager'].save_ir_json('phase2b','protocol_flows',state['protocol_flows'])
        state['config'] = {**state['config'],'task':task}
        state['output_manager'].save_ir_json('phase0','config',task)
        state['output_manager'].save_ir_json('phase2b','blueprint',bp)
        result = original(state)
        # Phase 2B normally renders BFMs; resumed Phase 3 bypasses that node.
        # Render AFTER Phase 3 applies native haven.json BFM parameter overrides.
        from haven.dsl.schema import BFMConfig
        from haven.utils.bfm_renderer import BFMRenderer
        result['bfm_components'] = BFMRenderer().render_all([
            BFMConfig(**b) for b in result['blueprint'].get('bfm_configs',[])])
        return result
    graph.node_testbench_gen = checked
