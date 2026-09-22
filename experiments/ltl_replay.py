"""Connect the RVProbe native monitor to the experiment simulation transport."""
import backend_imports
from rvprobe.backend.replay import monitor

def install(components, frames, design, *, audit=None):
    metas = [row['ltl'] for row in frames if 'ltl' in row]
    if not metas:
        return None
    if len(metas)!=1 or len({r['segment'] for r in frames})!=1:
        raise ValueError('native LTL replay requires one independently isolated sequence')
    meta = metas[0]
    name, code = monitor(meta,design,audit=audit)
    components['rvp_ltl_monitor'] = code
    components['filelist'] += f'\n{design.top}_rvp_ltl_monitor.sv\n'
    connections = ['.clock(rvp_pin_'+design.clock+')', '.reset(vif.rvp_reset_request)',
                   '.rvp_active(vif.rvp_ltl_active)']
    connections += [f'.{p.name}(rvp_observed.{p.name})' for p in design.data_ports]
    addition = f'initial vif.rvp_goal_mode=1;\n{name} u_rvp_ltl('+', '.join(connections)+');\n'
    components['top'] = components['top'].replace('endmodule',addition+'endmodule')
    return meta
