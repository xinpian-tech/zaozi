"""Offline migration of HAVEN's legacy kind/addr/data/strb item aliases.

This translates an existing baseline API, never chooses new stimulus values.
The output is deliberately NOT a passing Stage-1; compile it before registration.
"""
import argparse
import json
from pathlib import Path
import re
import shutil

from run_records import fingerprint, save


def translate(block, bus):
    kinds = list(re.finditer(r'\bkind\s*==\s*(?:\w+::)?(WRITE|READ)\b', block))
    if not kinds:
        if re.search(r'\b(kind|addr|data|strb)\b', block):
            raise ValueError('legacy payload without an explicit READ/WRITE kind')
        return block
    if len(kinds) != 1:
        raise ValueError('ambiguous legacy transaction kind')
    match = kinds[0]; write = match[1] == 'WRITE'
    block = block[:match.start()] + bus['we'] + (' == 1' if write else ' == 0') + block[match.end():]
    aliases = {'addr': bus['awaddr'] if write else bus['araddr'],
               'data': bus['data'], 'strb': bus['wstrb']}
    return re.sub(r'\b(addr|data|strb)\b', lambda m: aliases[m[0]], block)


def migrate_sv(code, bus):
    return re.sub(r'(\.randomize\(\)\s+with\s*\{)([^{}]*)(\})',
                  lambda m: m[1] + translate(m[2], bus) + m[3], code)


def migrate_dsl(value, bus):
    if isinstance(value, list):
        return [migrate_dsl(v, bus) for v in value]
    if not isinstance(value, dict):
        return value
    result = {k: migrate_dsl(v, bus) for k, v in value.items()}
    if isinstance(value.get('constraints'), list) and value['constraints']:
        text = ';'.join(value['constraints'])
        result['constraints'] = translate(text, bus).split(';')
    return result


def item_fields(code, fields):
    allowed = {f['name']: f for f in fields}
    # Remove legacy random knobs but retain nonrandom members referenced by
    # monitors, diagnostics and existing expected-output checks.
    code = re.sub(r'\brand\s+(?=[^;]+;)', '', code)
    missing = []
    for name, field in allowed.items():
        width = f"[{field['width']-1}:0] " if field['width'] > 1 else ''
        declaration = ('rand ' if field['is_rand'] else '') + 'logic ' + width + name + ';'
        pattern = r'\b(?:bit|logic)\s*(?:\[\d+:0\]\s*)?' + re.escape(name) + r'\s*;'
        code, count = re.subn(pattern, declaration, code)
        if count > 1:
            raise ValueError('duplicate item declaration: ' + name)
        if not count:
            missing.append(declaration)
    return code.replace('`uvm_object_utils', '\n'.join(missing) + '\n  `uvm_object_utils', 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', type=Path, required=True, help='offline refreshed AXI Stage-1')
    parser.add_argument('--legacy-stage', type=Path, required=True, help='original alias definitions')
    parser.add_argument('--haven-root', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    import sys
    sys.path.insert(0, str(args.haven_root.resolve()/'src'))
    from haven.utils.protocol_driver_renderer import _build_axi4lite_bus_context
    from haven.utils.transaction_contract import validate_item_contract
    stage = args.stage.resolve(); out = args.out.resolve()
    bp = json.loads((stage/'ir/phase2b_blueprint.json').read_text())
    bus = _build_axi4lite_bus_context(bp)
    top = bp['module_name']
    legacy = (args.legacy_stage/'final'/f'{top}_seq_item.sv').read_text()
    # Admit only the known legacy alias relationship, not guessed signal names.
    for lhs, rhs in ((bus['awaddr'], 'addr'), (bus['araddr'], 'addr'),
                     (bus['data'], 'data'), (bus['wstrb'], 'strb')):
        pattern = r'\b'+re.escape(lhs)+r'\s*==\s*'+rhs+r'\s*;'
        # Legacy oversized strobes may repeat the actual byte-lane mask.
        # The physical low-width mask is unchanged by removing that repetition.
        repeated_strobe = (rhs == 'strb' and
            re.search(r'\b'+re.escape(lhs)+r'\s*==\s*\{\d+\{strb\}\}\s*;', legacy) and
            re.search(r'\bbit\s*\['+str(next(f['width'] for f in bp['data_contracts']['seq_item_fields'] if f['name']==lhs)-1)+r':0\]\s*strb\s*;', legacy))
        if not re.search(pattern, legacy) and not repeated_strobe:
            raise ValueError('legacy alias relation not established: ' + lhs)
    if not re.search(r'if\s*\(kind\s*==\s*WRITE\)', legacy):
        raise ValueError('legacy WRITE discriminator missing')
    if not re.search(re.escape(bus['we'])+r"\s*==\s*1'b1", legacy) or not re.search(re.escape(bus['we'])+r"\s*==\s*1'b0", legacy):
        raise ValueError('legacy write-enable mapping missing')
    out.mkdir(parents=True, exist_ok=False)
    for name in ('ir', 'final'):
        shutil.copytree(stage/name, out/name)
    changes = {}
    def replace(path, code):
        before = path.read_text()
        if before != code:
            changes[str(path.relative_to(out))] = {'before': fingerprint(before), 'after': fingerprint(code)}
            path.write_text(code)
    item = item_fields((out/'final'/f'{top}_seq_item.sv').read_text(), bp['data_contracts']['seq_item_fields'])
    errors = validate_item_contract(item, bp['data_contracts']['seq_item_fields'])
    if errors:
        raise ValueError('; '.join(errors))
    replace(out/'final'/f'{top}_seq_item.sv', item)
    replace(out/'ir/phase5_components/seq_item.sv', item)
    for folder in (out/'final', out/'ir/phase4_sequences', out/'ir/phase5_sequences'):
        for path in folder.glob('*.sv'):
            if folder.name == 'final' and not path.name.startswith('sequence_'):
                continue
            replace(path, migrate_sv(path.read_text(), bus))
    dsl_path = out/'ir/phase4b_dsl_sequences.json'
    dsl = json.loads(dsl_path.read_text())
    save(dsl_path, migrate_dsl(dsl, bus))
    save(out/'ir/api-migration.json', dict(status='requires_recompilation', model_calls=0,
         source_stage=str(stage), legacy_stage=str(args.legacy_stage.resolve()), changes=changes,
         policy='Translate existing transaction aliases; preserve literals, sequence names and all output checks. Both arms share the migrated baseline.'))
    save(out/'ir/phase5_compile_check_result.json', {'compile_passed': False, 'reason': 'API migration requires recompilation'})
    print(json.dumps({'stage': str(out), 'files_changed': len(changes), 'model_calls': 0}))


if __name__ == '__main__':
    main()
