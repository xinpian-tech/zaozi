"""Separate stimulus implementation from the shared external device model.

Roles describe the installed generic templates, never a benchmark or an intent.
Responses are overapproximated in formal and checked against the live model in
replay. A symbolic response is NOT permission to override an external device.
"""

VERSION = 'shared-environment-conformance-v1'
BFM_ROLES = {
    'uart_serial': 'stimulus',
    'gpio': 'stimulus-with-electrical-feedback',
    'i2c_slave': 'response',
    'spi_slave': 'response',
    'wishbone_slave': 'response',
    'sdram_model': 'response',
    'mii_phy': 'stimulus-with-clocked-loopback',
}


def derive_policy(blueprint, boundary=None):
    from environment_contract import INDEPENDENT
    if boundary not in (None, INDEPENDENT):
        raise ValueError('unsupported DUT boundary')
    contract = blueprint.get('environment_contract') or {}
    bfms = blueprint.get('bfm_configs') or []
    agents = (blueprint.get('topology') or {}).get('agents') or []
    if (bfms or agents) and contract.get('version') != 'pin-ownership-v1':
        raise ValueError('environment roles require verified pin ownership')
    retained, roles, blockers = set(), [], []
    resolved_stimulus, scheduled_bfm_clocks = set(), set()
    seen = set()
    for bfm in bfms:
        protocol = bfm['protocol']
        if protocol in seen:
            raise ValueError('multiple BFM instances require instance-qualified ownership')
        seen.add(protocol)
        role = BFM_ROLES.get(protocol)
        if role is None:
            raise ValueError('unclassified external model: '+protocol)
        pins = sorted(p for p, owner in contract.get('bfm_owned', {}).items()
                      if owner == 'bfm:'+protocol)
        roles.append({'protocol': protocol, 'role': role, 'pins': pins})
        if role == 'response':
            retained.update(pins)
        elif role == 'stimulus-with-clocked-loopback':
            mapping = bfm.get('signals',{})
            if not {'rxd','rx_dv','rx_er','rx_clk','tx_clk','col','crs'} <= mapping.keys():
                raise ValueError('MII adapter requires explicit signal roles')
            resolved_stimulus.update(mapping[k] for k in ('rxd','rx_dv','rx_er'))
            scheduled_bfm_clocks.update(mapping[k] for k in ('rx_clk','tx_clk'))
            retained.update(mapping[k] for k in ('col','crs'))
    reactive = [a['name'] for a in agents if a.get('mode') == 'reactive']
    retained.update(p for p, owner in contract.get('agent_owned', {}).items() if owner in reactive)
    # Resolved wires (e.g. I2C SCL) are external electrical responses, not a
    # separate symbolic bus driver. Their native pullup/output-enable stays live.
    retained.update(p for p, owner in contract.get('infrastructure_owned', {}).items()
                    if owner == 'environment:pad')
    clocks = {c['port'] for c in blueprint.get('clock_schedule', [])}
    if retained & clocks:
        blockers.append('response-owned clocks need an explicit phase adapter')
    if scheduled_bfm_clocks - clocks:
        blockers.append('BFM event clocks missing from shared clock schedule')
    result = {'version': VERSION, 'bfms': roles, 'reactive_agents': reactive,
            'reactive_agent_pins': {p: owner for p, owner in contract.get('agent_owned', {}).items() if owner in reactive},
            'retained_inputs': sorted(retained), 'blockers': blockers,
            'resolved_stimulus_inputs': sorted(resolved_stimulus),
            'scheduled_bfm_clocks': sorted(scheduled_bfm_clocks),
            'formal_response_model': 'overapproximation under shared electrical/reset assumptions',
            'acceptance': 'original LTL must hit on live four-state IO when validated source provenance is present; legacy schedules require exact known waveform replay; response pins are never stimulus',
            'stimulus_policy': 'generator-specific sequence production, shared environment and simulation acceptance'}
    if boundary == INDEPENDENT:
        result.update(boundary=boundary, retained_inputs=[], resolved_stimulus_inputs=[],
            blockers=[], formal_response_model='none; data input pins are stimulus',
            acceptance='exact input replay and original native LTL Cover hit; no response-input exemptions',
            stimulus_policy='RVProbe: LTL over DUT IO, direct witness replay; HAVEN: native transaction/DSL templates')
        result['stimulus_interfaces'] = {
            'rvprobe':'Express LTL over DUT IO. Trusted generation replays solved input events directly, without native transaction scheduling.',
            'haven':'Generate native DSL transactions using the fixed driver and BFM APIs; raw replay storage is not a public DSL API.',
            'common':'Same original DUT, fixed clock/reset/static environment, coverage metrics and iteration budgets. Interface expressiveness is method-specific.',
        }
    return result


def require_supported(policy):
    if policy.get('version') != VERSION:
        raise ValueError('unsupported environment conformance policy')
    if policy['blockers']:
        raise ValueError('external environment adapter required: '+'; '.join(policy['blockers']))
