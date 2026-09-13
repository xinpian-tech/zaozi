"""Trusted environment inputs shared by formal and simulation backends."""
from event_trace import validate_clocks
from math import lcm

VERSION = 'shared-event-environment-v1'
INDEPENDENT = 'independent-dut-v1'


def independent_environment(env):
    """Keep clock/reset/static policy, but put the boundary at the DUT pins."""
    from copy import deepcopy
    result = deepcopy(env)
    result.update(boundary=INDEPENDENT, open_drain=[], feedback=[], passive_open_drain=[])
    return result


def passive_open_drain(blueprint):
    """Pins resolved only by the DUT and pullup, with no external producer."""
    ownership = blueprint.get('environment_contract') or {}
    infrastructure = ownership.get('infrastructure_owned', {})
    external = set(ownership.get('bfm_owned', {})) | set(ownership.get('agent_owned', {}))
    return sorted(p['input'] for p in blueprint.get('open_drain_connections', [])
                  if infrastructure.get(p['input']) == 'environment:pad' and p['input'] not in external)


def validate_environment(design, env):
    if env.get('version') != VERSION or set(env) - {'version','clocks','static','extra_resets','open_drain','feedback','passive_open_drain','boundary'}:
        raise ValueError('unsupported environment contract')
    if 'boundary' in env:
        if env['boundary'] != INDEPENDENT:
            raise ValueError('unsupported DUT boundary')
        if any(env.get(k) for k in ('open_drain','feedback','passive_open_drain')):
            raise ValueError('independent DUT inputs cannot be resolved by external feedback')
    ports = {p.name:p for p in design.ports}
    clocks = env['clocks']
    validate_clocks(clocks)
    names = {c['port'] for c in clocks}
    if design.clock not in names:
        raise ValueError('environment omits primary clock')
    for name in names:
        if name not in ports or ports[name].direction != 'input' or ports[name].width != 1:
            raise ValueError('clock must identify a scalar input')
        if ports[name].kind != 'clock':
            raise ValueError('environment clock must have Clock role in IO manifest')
    if names != {p.name for p in design.ports if p.kind == 'clock'}:
        raise ValueError('environment schedule differs from IO clock roles')
    claimed = set(names) | {design.reset}
    for name, value in env.get('static', {}).items():
        if name in claimed or name not in ports or ports[name].direction != 'input':
            raise ValueError('static pin conflicts with clock/reset or is not an input')
        if type(value) is not int or not 0 <= value < 1 << ports[name].width:
            raise ValueError(f'static {name} must be an integer fitting {ports[name].width} bits; got {value!r}')
        claimed.add(name)
    for reset in env.get('extra_resets', []):
        if set(reset) != {'port','active_low'} or type(reset['active_low']) is not bool:
            raise ValueError('invalid secondary reset')
        name = reset['port']
        if name in claimed or name not in ports or ports[name].direction != 'input' or ports[name].width != 1:
            raise ValueError('secondary reset has invalid or conflicting ownership')
        claimed.add(name)
    for pad in env.get('open_drain', []):
        if set(pad) != {'input','output','enable_n'}:
            raise ValueError('invalid split open-drain connection')
        for key,direction in [('input','input'),('output','output'),('enable_n','output')]:
            p = ports.get(pad[key])
            if p is None or p.direction != direction or p.width != 1:
                raise ValueError('open-drain metadata differs from RTL IO')
        if pad['input'] in claimed:
            raise ValueError('open-drain input has conflicting owners')
        claimed.add(pad['input'])
    passive = env.get('passive_open_drain', [])
    if (not isinstance(passive, list) or any(not isinstance(p, str) for p in passive) or
            len(set(passive)) != len(passive) or not set(passive) <= {p['input'] for p in env.get('open_drain', [])}):
        raise ValueError('passive open-drain pins must name distinct resolved connections')
    for connection in env.get('feedback', []):
        if set(connection) != {'input','output','enable'}:
            raise ValueError('invalid output-enable feedback connection')
        pins = [ports.get(connection[k]) for k in ('input','output','enable')]
        if any(p is None for p in pins) or [p.direction for p in pins] != ['input','output','output'] or len({p.width for p in pins}) != 1:
            raise ValueError('output-enable feedback differs from RTL IO')
        if connection['input'] in claimed:
            raise ValueError('feedback input has conflicting owners')
        claimed.add(connection['input'])
    return env


def formal_assumptions(design, env):
    validate_environment(design, env)
    ports = {p.name:p for p in design.ports}
    terms = [f"{name} == {ports[name].width}'h{value:x}" for name,value in env.get('static', {}).items()]
    terms += [f"reset || {r['port']} == 1'b{int(r['active_low'])}" for r in env.get('extra_resets', [])]
    terms += [(f"reset || {p['input']} == ({p['enable_n']} | {p['output']})"
               if p['input'] in env.get('passive_open_drain', []) else
               f"reset || !{p['input']} || {p['enable_n']} || {p['output']}")
              for p in env.get('open_drain', [])]
    terms += [f"reset || ({p['input']} & {p['enable']}) == ({p['output']} & {p['enable']})" for p in env.get('feedback', [])]
    return terms


def reset_sequence(design, config):
    env = validate_environment(design, config['environment'])
    clocks = env['clocks']
    quantum = validate_clocks(clocks)
    periods = {c['port']:c['period_ps'] for c in clocks}
    superperiod = lcm(*periods.values())
    end = config['reset_cycles'] * periods[design.clock]
    end = (end + superperiod-1)//superperiod*superperiod
    levels = dict(config['idle'])
    levels.update(env.get('static', {}))
    for r in env.get('extra_resets', []): levels[r['port']] = int(not r['active_low'])
    lines = ["reset 1'b1"]
    lines += [f"{p.name} {p.width}'h{levels[p.name]:x}" for p in design.data_ports
              if p.direction == 'input' and p.name not in periods]
    lines += [str(end//quantum), "reset 1'b0"]
    lines += [f"{r['port']} 1'b{int(r['active_low'])}" for r in env.get('extra_resets', [])]
    return '\n'.join(lines + ['$']) + '\n'


def verify_solver_environment(job, design, config):
    """A rehashed prepared job must not change the trusted replay environment."""
    env = config.get('environment')
    if not env:
        if job.get('clocks') or job.get('environmentAssumptions'):
            raise ValueError('prepared event environment has no matching replay policy')
        return
    validate_environment(design, env)
    quantum = validate_clocks(env['clocks'])
    clocks = [{'port':'clock' if c['port']==design.clock else c['port'],
               'factor':c['period_ps']//quantum} for c in env['clocks']]
    if (job.get('clocks') != clocks or job.get('resetSequence') != reset_sequence(design,config) or
            job.get('environmentAssumptions') != formal_assumptions(design,env)):
        raise ValueError('prepared clock/reset/assumptions differ from replay environment')


def verify_shared_environment(design, env, blueprint):
    """Never enable a new replay environment merely by adding a JSON key."""
    validate_environment(design,env)
    evidence = blueprint.get('rtl_environment_evidence')
    if not evidence or evidence.get('top') != design.top:
        raise ValueError('shared environment lacks elaborated RTL evidence; regenerate Stage-1 preflight')
    from cycle_replay import digest
    from pathlib import Path
    if {str(p):digest(p) for p in design.sources} != evidence['source_sha256']:
        raise ValueError('environment RTL evidence differs from design sources')
    expected_clocks = sorted(blueprint.get('clock_schedule',[]),key=lambda c:c['port'])
    if sorted(env['clocks'],key=lambda c:c['port']) != expected_clocks:
        raise ValueError('formal and native clock schedules differ')
    resets = [{'port':r['signal'],'active_low':r['level']=='low'} for r in blueprint.get('extra_resets',[]) or []]
    if sorted(env.get('extra_resets',[]),key=lambda r:r['port']) != sorted(resets,key=lambda r:r['port']):
        raise ValueError('formal and native secondary resets differ')
    if env.get('static',{}) != (blueprint.get('static_signals') or {}):
        raise ValueError('formal and native static inputs differ')
    if env.get('boundary') == INDEPENDENT:
        # Original ownership still belongs to the frozen native sequence
        # producers. It must not restrict the shared raw DUT-pin transport.
        return
    pads = [{k:p[k] for k in ('input','output','enable_n')} for p in blueprint.get('open_drain_connections',[])]
    if sorted(env.get('open_drain',[]),key=lambda p:p['input']) != sorted(pads,key=lambda p:p['input']):
        raise ValueError('formal and native open-drain connections differ')
    if sorted(env.get('passive_open_drain', [])) != passive_open_drain(blueprint):
        raise ValueError('formal and native passive pad ownership differs')
    if sorted(env.get('feedback',[]),key=lambda p:p['input']) != sorted(blueprint.get('feedback_connections',[]),key=lambda p:p['input']):
        raise ValueError('formal and native output-enable feedback differs')
