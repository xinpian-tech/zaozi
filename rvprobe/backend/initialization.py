"""Explicitly load the witness encoder's initial state before DUT reset.

JG 2021 ignores the generated SV declaration initializers. This adapter accepts
only Yosys's flattened, single-assignment FF form. It adds a solver-only boot
input, loads the generated initial values, then runs the unchanged frozen reset
sequence at the original clock phase. It does not initialize unknown DUT data.
"""
import math
import re
from copy import deepcopy

BOOT = 'rvp_encoded_boot'
GLOBAL_CLOCK = 'rvp_encoded_tick'
NAME = r'(?:\\[^\s]+|[A-Za-z_]\w*)'
INITIAL = re.compile(r'(?m)^\s*reg\s+(?:\[\d+:\d+\]\s+)?('
                     + NAME + r')\s*=\s*(\d+\x27(?:h[0-9a-fA-F]+|b[01]+|d[0-9]+))\s*;')
FF = re.compile(r'(always\s*@\((?:posedge|negedge)\s+[^)]+\)\s*)('
                + NAME + r')\s*<=\s*([^;]+);')


def canonicalize_output_aliases(model, top):
    """Split each identical output SigSpec only once before xprop.

    Yosys 0.67 can export a duplicate as d=(canonical_x ? X : canonical_d),
    x=0. In 2-state FPV that is a fresh arbitrary value marked as known.
    Canonicalize structural aliases, including syntactically different atoms.
    This changes naming only, never DUT state, reset, or predicate truth.
    """
    mapped = deepcopy(model)
    module = mapped['modules'][top]
    canonical, aliases = {}, {}
    outputs = [(name, port) for name, port in module['ports'].items() if port['direction'] == 'output']
    # Prefer the trusted, simple atom identifiers for goal/environment references.
    for name, port in sorted(outputs, key=lambda row: (not row[0].startswith('rvp_encoded_atom_'), row[0])):
        bits = tuple(port['bits'])
        if bits not in canonical:
            canonical[bits] = name
            continue
        aliases[name] = canonical[bits]
        # init is stored on netnames, not cells. Removing an alias must never
        # erase a real RTL initializer (or turn a known bit into arbitrary state).
        nets = module.setdefault('netnames', {})
        initial = nets.get(name, {}).get('attributes', {}).get('init')
        if initial is not None:
            target = nets.setdefault(canonical[bits], {'bits':list(bits),'attributes':{}})
            attributes = target.setdefault('attributes', {})
            previous = attributes.get('init', 'x' * len(bits))
            if len(initial) != len(bits) or len(previous) != len(bits):
                raise ValueError('output alias initializer width mismatch')
            if any(a in '01' and b in '01' and a != b for a,b in zip(previous,initial)):
                raise ValueError('conflicting output alias initializers')
            attributes['init'] = ''.join(b if a == 'x' else a for a,b in zip(previous,initial))
        del module['ports'][name]
        module.get('netnames', {}).pop(name, None)
    return mapped, aliases


def remap_encoded_expression(expression, aliases):
    return re.sub(r'\b([A-Za-z_]\w*)_([dx])\b',
                  lambda m: aliases.get(m[1], m[1]) + '_' + m[2], expression)


def pack_output_bits(model, top):
    """Export each distinct output bit once, including overlapping slices.

    Whole-port deduplication cannot handle an atom aliasing bus[0]. Give xprop
    a single non-overlapping output SigSpec and bind predicates to its rails.
    Keep all original netnames/initializers and cells: this is wiring only.
    """
    mapped = deepcopy(model)
    module = mapped['modules'][top]
    name = 'rvp_encoded_outputs'
    if name in module['ports'] or name in module.get('netnames', {}):
        raise ValueError('packed output name collision')
    bits, indices, bindings = [], {}, {}
    for port_name, port in list(module['ports'].items()):
        if port['direction'] != 'output':
            continue
        bindings[port_name] = []
        for bit in port['bits']:
            if bit not in indices:
                indices[bit] = len(bits)
                bits.append(bit)
            bindings[port_name].append(indices[bit])
        del module['ports'][port_name]
    if not bits:
        raise ValueError('no output bits to encode')
    module['ports'][name] = {'direction': 'output', 'bits': bits}
    module.setdefault('netnames', {})[name] = {
        'hide_name': 0, 'bits': bits, 'attributes': {}}
    return mapped, bindings


def bind_packed_expression(expression, bindings):
    """Preserve LSB-first JSON bit ordering in SV scalar/vector expressions."""
    def replace(match):
        if match[1] not in bindings:
            raise ValueError('encoded expression references an unmapped output')
        bits = [f'rvp_encoded_outputs_{match[2]}[{i}]'
                for i in reversed(bindings[match[1]])]
        return bits[0] if len(bits) == 1 else '{' + ', '.join(bits) + '}'
    return re.sub(r'\b([A-Za-z_]\w*)_([dx])\b', replace, expression)


def audit_encoded_goal_rails(model, top, expressions):
    """Reject combinational X decoders/undriven bits feeding claimed-known rails.

    FF state is checked by the explicit value/mask initialization contract;
    this audit catches X reintroduction at the exported combinational boundary.
    It is deliberately conservative, not an equivalence proof for arbitrary RTL.
    """
    module = model['modules'][top]
    predicates = re.sub(r'@\([^)]*\)', '', ' '.join(expressions))
    names = sorted(set(re.findall(r'\b[A-Za-z_]\w*_[dx]\b', predicates)))
    ports = module['ports']
    if any(name not in ports or ports[name]['direction'] != 'output' for name in names):
        raise ValueError('encoded goal rail missing from outputs')
    inputs = {b for p in ports.values() if p['direction'] == 'input' for b in p['bits']}
    drivers = {}
    state = {'$dff','$dffe','$sdff','$sdffe','$sdffce','$adff','$adffe','$aldff','$aldffe','$ff'}
    for cell in module.get('cells', {}).values():
        fanin = [b for name,direction in cell['port_directions'].items() if direction == 'input'
                 for b in cell['connections'][name]]
        for name,direction in cell['port_directions'].items():
            if direction == 'output':
                for bit in cell['connections'][name]:
                    drivers.setdefault(bit, []).append([] if cell['type'] in state else fanin)
    pending = [b for name in names for b in ports[name]['bits']]
    seen = set()
    while pending:
        bit = pending.pop()
        if bit in seen:
            continue
        seen.add(bit)
        if bit in ('x','z'):
            raise ValueError('encoded goal rail reintroduces an unencoded X/Z value')
        if bit in ('0','1') or bit in inputs:
            continue
        if bit not in drivers or len(drivers[bit]) != 1:
            raise ValueError('encoded goal rail has an undriven or multiply driven bit')
        pending.extend(drivers[bit][0])
    return dict(policy='no-combinational-x-reentry-v1', rails=names, checked_bits=len(seen))


def preserve_single_driver_masks(model, top):
    """tribuf -formal drops a lone driver's enable. Preserve Z-as-X instead.

    Input is bit-split before this pass; overlapping multi-driver bits remain
    for the subsequent noncontention lowering. No source RTL is edited.
    """
    mapped=deepcopy(model)
    module=mapped['modules'][top]
    groups={}
    for name,cell in module['cells'].items():
        if cell['type'] == '$_TBUF_':
            ports=cell['connections']
            if any(len(ports[k])!=1 for k in ('A','E','Y')):
                raise ValueError('expected bit-split tristate driver')
            groups.setdefault(tuple(ports['Y']),[]).append(name)
    changed=[]
    for names in groups.values():
        if len(names)!=1:continue
        name=names[0];cell=module['cells'][name];ports=cell['connections']
        cell.update(type='$mux',parameters={'WIDTH':'1'},
            port_directions={'A':'input','B':'input','S':'input','Y':'output'},
            connections={'A':['x'],'B':ports['A'],'S':ports['E'],'Y':ports['Y']})
        changed.append(name)
    return mapped,changed


def expose_collision_guards(model, top):
    """Encode only the newly generated tribuf collision predicates as outputs.

    xprop does not support $assume cells with X-sensitive inputs. Export their
    exact Boolean predicates, then require known-true encoded rails in JG.
    Original RTL assertions are rejected before tribuf is run.
    """
    mapped = deepcopy(model)
    module = mapped['modules'][top]
    guards = []
    for old, cell in list(module['cells'].items()):
        if cell['type'] != '$assume':
            continue
        ports = cell['connections']
        if not old.startswith('$tribuf_conflict$') or ports['EN'] != ['1'] or len(ports['A']) != 1:
            raise ValueError('unexpected non-collision assumption in diagnostic model')
        name = f'rvp_encoded_guard_{len(guards)}'
        if name in module['ports'] or name in module['netnames']:
            raise ValueError('collision guard name collision')
        module['ports'][name] = {'direction': 'output', 'bits': ports['A']}
        module['netnames'][name] = {'hide_name': 0, 'bits': ports['A'], 'attributes': {}}
        guards.append(name)
        del module['cells'][old]
    return mapped, guards


def global_ff_commands(model, top):
    """Expose Yosys's implicit global-tick FFs as a real diagnostic clock.

    async2sync uses these cells for latch history. They must not be black-boxed
    by JG or silently tied to one of the DUT's unrelated clock domains.
    """
    if set(model['modules']) != {top}:
        raise ValueError('expected one flattened clock-mapping module')
    module = model['modules'][top]
    cells = sorted(name for name, cell in module['cells'].items() if cell['type'] == '$ff')
    if not cells:
        return [], [], model
    if GLOBAL_CLOCK in module['netnames']:
        raise ValueError('global clock name collision')
    names = []
    mapped = deepcopy(model)
    mapped_module = mapped['modules'][top]
    mapped_cells = mapped_module['cells']
    bits = [bit for net in module['netnames'].values() for bit in net['bits'] if type(bit) is int]
    bits += [bit for cell in module['cells'].values() for port in cell.get('connections', {}).values()
             for bit in port if type(bit) is int]
    tick = max([1, *bits]) + 1
    mapped_module.setdefault('ports', {})[GLOBAL_CLOCK] = {'direction': 'input', 'bits': [tick]}
    mapped_module['netnames'][GLOBAL_CLOCK] = {'hide_name': 0, 'bits': [tick], 'attributes': {}}
    for index, old in enumerate(cells):
        name = f'rvp_global_ff_{index}'
        if name in module['cells']:
            raise ValueError('global FF name collision')
        names.append(name)
        # Names in flattened RTL can contain literal backslashes. Rename JSON
        # dictionary keys, never interpolate these untrusted identifiers in a
        # Yosys command string.
        mapped_cells[name] = mapped_cells.pop(old)
        # Update type and clock atomically. Intermediate `setparam`/`connect`
        # commands are invalid: a $dff without CLK (or $ff with CLK) fails the
        # frontend's per-command cell validation.
        mapped_cells[name]['type'] = '$dff'
        mapped_cells[name].setdefault('parameters', {})['CLK_POLARITY'] = '1'
        mapped_cells[name].setdefault('port_directions', {})['CLK'] = 'input'
        mapped_cells[name].setdefault('connections', {})['CLK'] = [tick]
    return ['select -assert-none t:$ff'], names, mapped


def materialize_initializers(code, reset_sequence, clocks):
    if BOOT in code:
        raise ValueError('encoded boot name already in use')
    initial = dict(INITIAL.findall(code))
    if len(initial) != len(INITIAL.findall(code)):
        raise ValueError('duplicate initialized register')
    # Any unsupported declaration must fail closed, not silently lose its init.
    if len(initial) != len(re.findall(r'(?m)^\s*reg\s+[^;\n]*=', code)):
        raise ValueError('unsupported encoded register initializer')
    if not initial:
        raise ValueError('encoded model has no explicit initial state')
    for literal in initial.values():
        width, number = literal.split("'")
        if int(width) <= 0 or int(number[1:], {'b': 2, 'd': 10, 'h': 16}[number[0]]) >= 2**int(width):
            raise ValueError('invalid encoded initializer width')
    written = set()
    def replace(match):
        head, name, rhs = match.groups()
        if name not in initial:
            return match[0]
        if name in written:
            raise ValueError('multiple writers for initialized register')
        written.add(name)
        return (f'{head}if ({BOOT}) {name} <= {initial[name]};\n'
                f'    else {name} <= {rhs};')
    code = FF.sub(replace, code)
    if written != initial.keys():
        raise ValueError('initialized state is not a supported simple FF')
    header = re.search(r'\bmodule\s+\w+\s*\([^;]+\);', code)
    if not header or code.count('endmodule') != 1:
        raise ValueError('expected one flattened non-ANSI module')
    code = (code[:header.end()-2] + f', {BOOT});\ninput {BOOT};'
            + code[header.end():])
    factors = [c['factor'] for c in clocks]
    if not factors or any(type(f) is not int or f <= 0 for f in factors):
        raise ValueError('invalid boot clock factors')
    duration = 2 * math.lcm(*factors)
    if duration > 20000:
        raise ValueError('boot clock period too large')
    # Set the frozen reset inputs before boot, and repeat the *whole* original
    # sequence afterwards. Complete superperiods preserve every clock's phase.
    prefix = re.split(r'(?m)^\s*\d+\s*$', reset_sequence, maxsplit=1)[0]
    if not prefix.strip() or '$' in prefix:
        raise ValueError('missing frozen reset input prefix')
    sequence = (f'{BOOT} 1\'b1\n' + prefix + f'{duration}\n'
                f'{BOOT} 1\'b0\n' + reset_sequence)
    return code, sequence, dict(policy='explicit-encoded-initializers-v1',
                               boot_cycles=duration, initialized_registers=initial)
