"""Shared DUT-boundary mux for timed sequences; native agents/BFMs stay intact.

Native producers never write raw storage. The selected primary sequence controls
the mux; monitors observe the effective DUT pins in both execution modes.
"""
from copy import deepcopy
import re
from environment_contract import validate_environment
from haven_shared import replace_once


def verify_native_sources(blueprint, components, bfms):
    """Metadata is insufficient if a later repair changed the native sources."""
    from haven.dsl.schema import BFMConfig
    from haven.utils.bfm_renderer import BFMRenderer
    expected = BFMRenderer().render_all([BFMConfig(**b) for b in blueprint.get('bfm_configs',[])])
    if bfms != expected:
        raise ValueError('compiled BFM sources differ from the shared verified templates')
    owned = (blueprint.get('environment_contract') or {}).get('bfm_owned',{})
    for clock in blueprint['clock_schedule']:
        if clock['port'] in owned: continue
        signal = 'clk' if clock['port']==blueprint['clock']['port'] else 'env_clock_'+clock['port']
        command = f"forever #({clock['period_ps']//2} * 1ps) {signal} = ~{signal};"
        if components['top'].count(command) != 1:
            raise ValueError('compiled native clock source differs from shared clock schedule')


def select_primary_driver(blueprint, components):
    agents = blueprint.get('topology', {}).get('agents', []) or []
    bus = (blueprint.get('protocol_flows') or {}).get('bus_field_mapping') or {}
    bus_inputs = {bus.get(k) for k in ('cyc','stb','awvalid','arvalid','addr')} - {None,''}
    active = [a for a in agents if a.get('mode','active') in ('active','active_master')]
    candidates = [a for a in active if bus_inputs & set(a.get('input_signals', []))] if bus_inputs else active
    if not agents and 'driver' in components:
        return 'driver'
    if len(candidates) != 1:
        raise ValueError('environment needs an unambiguous primary sequence agent')
    key = candidates[0]['name'] + '__driver' if len(agents) > 1 else 'driver'
    if key not in components:
        raise ValueError('primary sequence driver is missing')
    contract = blueprint.get('environment_contract')
    if not contract:
        raise ValueError('multi-producer bench lacks a verified pin ownership contract')
    from haven.utils.environment_contract import validate_driver_ownership
    for agent in agents:
        name = agent['name'] + '__driver' if len(agents)>1 else 'driver'
        if agent.get('mode') == 'passive':
            if name in components:
                raise ValueError('passive agent has a driver')
            continue
        if name not in components:
            raise ValueError('active/reactive agent is missing its driver')
        errors = validate_driver_ownership(components[name], agent['name'], blueprint)
        if errors:
            raise ValueError('; '.join(errors))
    return key


def install_event_transport(components, design, config, driver_key, policy=None):
    env = validate_environment(design, config['environment'])
    from environment_contract import INDEPENDENT
    independent = env.get('boundary') == INDEPENDENT
    from environment_policy import derive_policy
    policy = policy if policy is not None else derive_policy({})
    if independent != (policy.get('boundary') == INDEPENDENT):
        raise ValueError('transport policy and DUT boundary differ')
    retained = set(policy['retained_inputs'])
    resolved_stimulus = set(policy.get('resolved_stimulus_inputs',[]))
    scheduled_bfm_clocks = set(policy.get('scheduled_bfm_clocks',[]))
    reactive_pins = policy['reactive_agent_pins']
    result = deepcopy(components)
    if any('rvp_raw_mode' in code for code in result.values()):
        raise ValueError('event transport already installed')
    clocks = {c['port'] for c in env['clocks']}
    inputs = [p for p in design.data_ports if p.direction=='input' and p.kind!='clock']
    if not retained <= {p.name for p in inputs}:
        raise ValueError('retained environment pins must be non-clock DUT inputs')
    outputs = [p for p in design.data_ports if p.direction=='output']
    fields = ['bit rvp_raw = 0;', 'bit rvp_reset;', 'bit rvp_formal_sample = 1;', 'bit rvp_ltl_active = 0;', 'int rvp_ordinal;', 'longint unsigned rvp_duration_ps;']
    storage = ['bit rvp_raw_mode = 0;', 'bit rvp_reset_request = 1;', 'bit rvp_primary_clock = 0;', 'bit rvp_goal_mode = 0;', 'bit rvp_ltl_active = 0;']
    for p in inputs:
        fields.append(f'bit [{p.width-1}:0] rvp_drive_{p.name};')
        storage.append(f'bit [{p.width-1}:0] rvp_drive_{p.name} = 0;')
        storage.append(f'wire [{p.width-1}:0] rvp_effective_{p.name};')
    for name in sorted(clocks):
        fields.append(f'bit rvp_clock_{name};')
        if name != design.clock:
            storage.append(f'bit rvp_drive_{name} = 0;')
            if not re.search(r'\b(?:logic|wire|bit)\s+(?:\[[^]]+\]\s*)?'+re.escape(name)+r'\s*[,;)]',result['interface']):
                storage.append(f'wire {name};')
    for p in outputs:
        fields += [f'bit [{p.width-1}:0] rvp_expected_{p.name}, rvp_mask_{p.name};']
    # Private, nonrandom replay storage. RVProbe fills every event explicitly;
    # HAVEN's transaction item and randomize() API do not expose these fields.
    # rvp_raw's constructor default keeps the untouched native baseline active.
    result['seq_item'] = replace_once(result['seq_item'], 'endclass', '\n'.join(fields)+'\nendclass')
    result['interface'] = replace_once(result['interface'], 'endinterface', '\n'.join(storage)+'\nendinterface')
    top = result['top']
    if re.search(r'\btime(?:unit|precision)\b', top):
        raise ValueError('explicit top time declarations require timebase validation')
    top = '`timescale 1ns/1ps\n' + top
    match = re.search(r'\b'+re.escape(design.top)+r'\s+u_dut\s*\((.*?)\)\s*;', top, re.S)
    if not match:
        raise ValueError('expected one original DUT instance for boundary mux')
    connections = {}
    for m in re.finditer(r'\.(\w+)\s*\(([^()]*)\)',match[1]):
        if m[1] in connections: raise ValueError('duplicate DUT connection')
        connections[m[1]] = m[2]
    if set(connections) != {p.name for p in design.ports}:
        raise ValueError('DUT connection list differs from IO manifest')
    if design.parameters:
        raise ValueError('event transport requires original default RTL parameters')
    reset_map = {r['port']:r['active_low'] for r in env.get('extra_resets', [])}
    pad_map = {p['input']:p for p in env.get('open_drain', [])}
    feedback_map = {p['input']:p for p in env.get('feedback', [])}
    decls, pins, observed = [], [], []
    for clock in env['clocks']:
        if clock['port'] in scheduled_bfm_clocks:
            name = 'rvp_native_clock_'+clock['port']
            decls.append(f"logic {name}=0; always #({clock['period_ps']//2} * 1ps) {name}=~{name};")
    for name in sorted(clocks - {design.clock}):
        # Native agents can observe independent clocks without driving them.
        if connections[name].strip() != f'vif.{name}':
            observed.append(f'assign vif.{name} = {connections[name]};')
    for p in design.ports:
        original = connections[p.name]
        if p.name in scheduled_bfm_clocks:
            original = 'rvp_native_clock_'+p.name
        if p.direction == 'output':
            pins.append(f'.{p.name}({original})')
            observed.append(f'assign rvp_observed.{p.name} = {original};')
            continue
        if p.name == design.clock: raw = 'vif.rvp_primary_clock'
        elif p.name == design.reset: raw = ('~' if design.reset_active_low else '')+'vif.rvp_reset_request'
        elif p.name in reset_map and not independent: raw = ('~' if reset_map[p.name] else '')+'vif.rvp_reset_request'
        # At the independent boundary a secondary reset is an explicitly
        # exposed input. Do not silently override it with the primary reset.
        # RVProbe's automatic reset rows already carry the required pin values.
        elif p.name in env.get('static', {}): raw = f"{p.width}'h{env['static'][p.name]:x}"
        else: raw = 'vif.rvp_drive_' + p.name
        if p.name in pad_map and p.name not in retained:
            pad = pad_map[p.name]
            raw = f'({raw} & (vif.{pad["enable_n"]} | vif.{pad["output"]}))'
        if p.name in feedback_map and p.name not in retained:
            connection = feedback_map[p.name]
            raw = f'(({raw} & ~vif.{connection["enable"]}) | (vif.{connection["output"]} & vif.{connection["enable"]}))'
        name = 'rvp_pin_'+p.name
        if p.name in retained or p.name in resolved_stimulus:
            raw = original
        decls += [f'wire [{p.width-1}:0] {name};', f'assign {name} = vif.rvp_raw_mode ? {raw} : ({original});']
        pins.append(f'.{p.name}({name})')
        if p.name not in (design.clock,design.reset): observed.append(f'assign rvp_observed.{p.name} = {name};')
        if p.name not in clocks and p.name != design.reset:
            observed.append(f'assign vif.rvp_effective_{p.name} = {name};')
    instance = f'{design.top} u_dut ('+', '.join(pins)+');'
    declaration = f'{design.top}_if rvp_observed(rvp_pin_{design.clock}, rvp_pin_{design.reset});'
    top = (top[:match.start()] + '\n'.join([*decls,declaration,*observed]) + '\n' +
           instance + top[match.end():])
    # Native/shared devices see the DUT clock/reset. In independent raw mode
    # their responses are disconnected: hold those inactive devices in reset
    # so arbitrary DUT requests cannot trip an unused device's protocol checks.
    # This mux is author-neutral; native-mode behavior and BFM sources stay fixed.
    clock_sources = {'clk': design.clock, **{'env_clock_'+c:c for c in clocks if c != design.clock}}
    for bfm in policy['bfms']:
        protocol = bfm['protocol']
        pattern = r'(\b'+re.escape(protocol)+r'_bfm\s+u_'+re.escape(protocol)+r'_bfm\s*\()(.*?)(\)\s*;)'
        def bind_device(match):
            body = match[2]
            clock = re.search(r'\.clk\((\w+)\)',body)
            if not clock or clock[1] not in clock_sources:
                raise ValueError('external device has an unsupported clock connection')
            body = replace_once(body,clock[0],'.clk(rvp_pin_'+clock_sources[clock[1]]+')')
            old_reset = '.rst('+('~' if design.reset_active_low else '')+design.reset+')'
            device_reset = ('~' if design.reset_active_low else '')+'rvp_pin_'+design.reset
            if independent:
                device_reset = "vif.rvp_raw_mode ? 1'b1 : ("+device_reset+')'
            body = replace_once(body,old_reset,'.rst('+device_reset+')')
            if protocol == 'mii_phy':
                adapter_ports = {'external_clock_mode','external_rx_clk','external_tx_clk',
                                 'external_stimulus_mode','external_rxd','external_rx_dv','external_rx_er'}
                connections = re.split(r',\s*(?=\.)',body.strip())
                kept = []
                for connection in connections:
                    pin = re.match(r'\.(\w+)\s*\(',connection)
                    if pin and pin[1] in adapter_ports:
                        if not re.fullmatch(r'\.\w+\s*\(\s*0\s*\)',connection.strip()):
                            raise ValueError('MII adapter port already has a non-default owner')
                    else:
                        kept.append(connection)
                body = ', '.join(kept)
                def mapped(port):
                    match = re.search(r'\.'+port+r'\(vif\.(\w+)\)',body)
                    if not match: raise ValueError('MII adapter requires a direct interface connection: '+port)
                    return match[1]
                rx,tx = mapped('rx_clk'),mapped('tx_clk')
                body += (f', .external_clock_mode(1\'b1), .external_rx_clk(rvp_pin_{rx}), .external_tx_clk(rvp_pin_{tx}), '
                         '.external_stimulus_mode(vif.rvp_raw_mode), '+
                         ', '.join(f'.external_{port}(vif.rvp_drive_{mapped(port)})' for port in ('rxd','rx_dv','rx_er')))
            return match[1]+body+match[3]
        top, count = re.subn(pattern,bind_device,top,flags=re.S)
        if count != 1:
            raise ValueError('external device instance missing or ambiguous: '+protocol)
    # Reactive UVM drivers also observe effective pins. Only their owned input
    # variables flow back to the native interface; stimulus drivers stay intact.
    device_configs = []
    if policy['reactive_agents']:
        device_lines = [f'{design.top}_if rvp_devices(rvp_pin_{design.clock}, rvp_pin_{design.reset});']
        for p in design.ports:
            if p.name in (design.clock, design.reset): continue
            if p.name in reactive_pins:
                device_lines.append(f'assign vif.{p.name} = rvp_devices.{p.name};')
            else:
                source = f'vif.{p.name}' if p.direction == 'output' else f'rvp_pin_{p.name}'
                device_lines.append(f'assign rvp_devices.{p.name} = {source};')
        top = replace_once(top,declaration,declaration+'\n'+'\n'.join(device_lines))
        for name in policy['reactive_agents']:
            device_configs.append(f'uvm_config_db#(virtual {design.top}_if)::set(null, "*m_{name}.m_driver", "vif", rvp_devices);')
    if len(re.findall(r'\brun_test\s*\(',top)) != 1:
        raise ValueError('expected one run_test for effective-pin observer binding')
    configs = '\n'.join(f'uvm_config_db#(virtual {design.top}_if)::set(null, "*{kind}*", "vif", rvp_observed);'
                        for kind in ('monitor','subscriber'))
    configs += '\n'+'\n'.join(device_configs)
    top = re.sub(r'\brun_test\s*\(',configs+'\n    run_test(',top,count=1)
    result['top'] = top
    writes = '\n'.join(f'vif.rvp_drive_{p.name} = item.rvp_drive_{p.name};' for p in inputs if p.name not in clocks | retained)
    edges = '\n'.join(f"vif.{'rvp_primary_clock' if name==design.clock else 'rvp_drive_'+name} = item.rvp_clock_{name};"
                       for name in sorted(clocks))
    checks = '\n'.join(
        f'if ($isunknown(vif.{p.name} & item.rvp_mask_{p.name})) '
        f'`uvm_fatal("WITNESS_X", $sformatf("row %0d port={p.name} actual=%h expected=%h mask=%h", '
        f'item.rvp_ordinal, vif.{p.name}, item.rvp_expected_{p.name}, item.rvp_mask_{p.name}))\n'
        f'if ((vif.{p.name} & item.rvp_mask_{p.name}) !== (item.rvp_expected_{p.name} & item.rvp_mask_{p.name})) '
        f'`uvm_fatal("WITNESS", $sformatf("row %0d port={p.name} actual=%h expected=%h mask=%h", '
        f'item.rvp_ordinal, vif.{p.name}, item.rvp_expected_{p.name}, item.rvp_mask_{p.name}))' for p in outputs)
    for p in sorted(retained):
        expected = f'item.rvp_drive_{p}'
        if p in env.get('passive_open_drain', []):
            pad = pad_map[p]
            # A supplemental edge holds stimulus, not the previous resolved
            # response. Formal samples still compare the exact solver witness.
            expected = f'(item.rvp_formal_sample ? {expected} : (vif.{pad["enable_n"]} | vif.{pad["output"]}))'
        checks += (f'\nif (!item.rvp_reset && vif.rvp_effective_{p} !== {expected}) '
                   f'`uvm_fatal("ENVIRONMENT_WITNESS", $sformatf("row %0d port={p} actual=%h expected=%h formal_sample=%b", '
                   f'item.rvp_ordinal, vif.rvp_effective_{p}, {expected}, item.rvp_formal_sample))')
    checks = 'if (!vif.rvp_goal_mode) begin\n'+checks+'\nend'
    if independent:
        # Input fidelity is mandatory even when the native Cover is the oracle.
        # A transport defect must not become an expensive model-repair loop.
        checks += '\n' + '\n'.join(
            f'if (vif.rvp_effective_{p.name} !== item.rvp_drive_{p.name}) '
            f'`uvm_fatal("INPUT_WITNESS", $sformatf("row %0d port={p.name} actual=%h expected=%h", '
            f'item.rvp_ordinal, vif.rvp_effective_{p.name}, item.rvp_drive_{p.name}))'
            for p in inputs)
    if policy['blockers']:
        checks = '`uvm_fatal("ENVIRONMENT_ADAPTER", "external model adapter is not implemented")\n'+checks
    fmt = ' '.join(['%h']*len(inputs)+['%b']*len(outputs))
    # Logged inputs are the formal row, clocks are logged separately as events.
    values = ''.join(', vif.rvp_effective_'+p.name for p in inputs) + ''.join(', vif.'+p.name for p in outputs)
    # Static inputs are environment preconditions, not free stimulus. Keep the
    # request intact and reject a contradiction BEFORE touching any DUT pin.
    # Do not silently clamp it, weaken INPUT_WITNESS, or rely on SV randomize()
    # failure (a caller can ignore its return value). Soft defaults above make
    # omitted fields valid; explicit contradictory requests still fail here.
    input_widths = {p.name:p.width for p in inputs}
    preconditions = '\n'.join(
        f"if (item.rvp_drive_{name} !== {input_widths[name]}'h{value:x}) "
        f'`uvm_fatal("CANDIDATE_ENVIRONMENT", $sformatf("row %0d port={name} requested=%h fixed=%h; '
        f'this input is fixed by the shared environment, not sequence-controlled", '
        f"item.rvp_ordinal, item.rvp_drive_{name}, {input_widths[name]}'h{value:x}))"
        for name,value in env.get('static',{}).items()) if independent else ''
    task = f'''
  task rvp_drive_event({design.top}_seq_item item);
    if (item.rvp_duration_ps < 2) `uvm_fatal("EVENT_TIME", "event duration must be at least 2ps")
    {preconditions}
    vif.rvp_reset_request = item.rvp_reset;
    vif.rvp_ltl_active = item.rvp_ltl_active;
    {writes}
    vif.rvp_raw_mode = 1;
    #1ps;
    $display("RVPROBE_SAMPLE %0d %0d %b {fmt}", item.rvp_ordinal, longint'($realtime / 1ps), {'~' if design.reset_active_low else ''}item.rvp_reset{values});
    {checks}
    {edges}
    #(item.rvp_duration_ps * 1ps - 1ps);
  endtask
'''
    code = result[driver_key]
    calls = re.findall(r'seq_item_port\.get_next_item\((\w+)\);', code)
    if len(calls)!=1: raise ValueError('expected one primary driver get_next_item')
    var = calls[0]
    hook = f'''seq_item_port.get_next_item({var});
      if ({var}.rvp_raw) begin
        rvp_drive_event({var});
        seq_item_port.item_done();
        continue;
      end
      vif.rvp_raw_mode = 0;'''
    code = replace_once(code,f'seq_item_port.get_next_item({var});',hook)
    result[driver_key] = replace_once(code,'endclass',task+'\nendclass')
    return result
