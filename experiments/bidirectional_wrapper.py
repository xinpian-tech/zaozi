"""Explicit resolved-wire adapter for imported bidirectional RTL, no DUT logic.

The original module and files are unchanged. Each inout gets external drive
data/bit enables and resolved sense ports. Z and contention remain native RTL
semantics in JG/VCS; a peripheral model must own external drive pins in replay.
"""
import argparse
from copy import deepcopy
import json
from pathlib import Path

from rtl_environment import import_ports
from run_records import save
from cycle_replay import digest
from sequence_framework import identifier


def render(top, wrapper, ports):
    identifier(top, 'RTL module'); identifier(wrapper, 'wrapper module')
    if top == wrapper: raise ValueError('wrapper must not replace original module')
    declarations, wires, connections, mapping = [], [], [], []
    names = {p['name'] for p in ports}
    for p in ports:
        name, width = identifier(p['name'],'port'), p['width']
        if type(width) is not int or width < 1: raise ValueError('invalid port width')
        if p['direction'] != 'inout':
            if p['direction'] not in ('input','output'): raise ValueError('invalid direction')
            declarations.append(f'{p["direction"]} wire [{width-1}:0] {name}')
            connections.append(f'.{name}({name})')
            continue
        added = [name+'_drive',name+'_enable',name+'_sense']
        if names.intersection(added): raise ValueError('split port name collision')
        names.update(added)
        declarations.extend([f'{direction} wire [{width-1}:0] {n}'
                             for direction,n in zip(('input','input','output'),added)])
        wires.append(f'tri [{width-1}:0] {name};')
        for bit in range(width):
            wires.append(f"assign {name}[{bit}] = {added[1]}[{bit}] ? {added[0]}[{bit}] : 1'bz;")
        wires.append(f'assign {added[2]} = {name};')
        connections.append(f'.{name}({name})')
        mapping.append(dict(port=name,width=width,drive=added[0],enable=added[1],sense=added[2]))
    if not mapping: raise ValueError('no bidirectional ports to adapt')
    return ('module '+wrapper+'(\n  '+',\n  '.join(declarations)+'\n);\n'+
            '\n'.join(wires)+'\n'+top+' original_dut('+', '.join(connections)+');\nendmodule\n'), mapping


def adapt(task, blueprint, ports, directory):
    """Resolve supported native BFM pads mechanically, without authoring stimuli."""
    if task.get('parameters'):
        raise ValueError('resolved IO wrapper does not yet support parameter overrides')
    task, blueprint = deepcopy(task), deepcopy(blueprint)
    directory = Path(directory).resolve()
    wrapper = task['module_name'] + '_resolved_io'
    source, mapping = render(task['module_name'], wrapper, ports)
    for pad in mapping:
        owners = [(b, key) for b in blueprint.get('bfm_configs', [])
                  for key, actual in b['signals'].items() if actual == pad['port']]
        if len(owners) != 1 or owners[0][0]['protocol'] != 'sdram_model' or owners[0][1] != 'dq':
            raise ValueError('inout requires one supported native BFM owner: ' + pad['port'])
        bfm = owners[0][0]
        bfm.setdefault('params', {})['split_io'] = True
        bfm['signals'].update(dq=pad['sense'], drive_data=pad['drive'], drive_enable=pad['enable'])
        for agent in blueprint.get('topology', {}).get('agents', []):
            for key in ('input_signals', 'output_signals', 'observed_environment_inputs'):
                agent[key] = [pad['sense'] if n == pad['port'] else n for n in agent.get(key, [])]
        for field in blueprint.get('data_contracts', {}).get('seq_item_fields', []):
            if field['name'] == pad['port']:
                field.update(name=pad['sense'], direction='output', is_rand=False)
    directory.mkdir(parents=True, exist_ok=False)
    path = directory / (wrapper + '.v')
    path.write_text(source)
    original = [(Path(task['root']) / s).resolve() for s in task['rtl_files']]
    metadata = dict(version='resolved-bidirectional-wrapper-v1', original_top=task['module_name'],
                    wrapper_top=wrapper, ports=mapping, original_ports=ports,
                    wrapper_file=str(path), wrapper_sha256=digest(path),
                    original_source_sha256={str(p): digest(p) for p in original})
    save(directory / 'adapter.json', metadata)
    task.update(module_name=wrapper, rtl_files=[str(p) for p in original]+[str(path)], io_adapter=metadata)
    blueprint['module_name'] = wrapper
    if blueprint.get('protocol_flows'):
        blueprint['protocol_flows']['bfm_configs'] = deepcopy(blueprint['bfm_configs'])
    # Native Phase 3 reapplies these; do not let an old pin map undo resolution.
    for bfm in blueprint.get('bfm_configs', []):
        override = task.get('bfm_overrides', {}).get(bfm['protocol'])
        if override is not None:
            if 'signals' in override: override['signals'] = deepcopy(bfm['signals'])
            if bfm.get('params', {}).get('split_io'):
                override.setdefault('params', {})['split_io'] = True
    return task, blueprint


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--task',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--wrapper',required=True)
    args=p.parse_args(); args.out=args.out.resolve(); args.out.mkdir(parents=True,exist_ok=False)
    task=json.loads(args.task.read_text())
    if task.get('parameters'):
        raise ValueError('resolved IO wrapper does not yet support parameter overrides')
    ports=import_ports(task,args.out/'original-io')
    source,mapping=render(task['module_name'],args.wrapper,ports)
    (args.out/'rtl').mkdir()
    path=args.out/'rtl'/(args.wrapper+'.v'); path.write_text(source)
    original=[(Path(task['root'])/s).resolve() for s in task['rtl_files']]
    metadata=dict(version='resolved-bidirectional-wrapper-v1',original_top=task['module_name'],
                  wrapper_top=args.wrapper,ports=mapping,original_ports=ports,wrapper_file=str(path),wrapper_sha256=digest(path),
                  original_source_sha256={str(p):digest(p) for p in original})
    save(args.out/'adapter.json',metadata)
    save(args.out/'task.json',{**task,'module_name':args.wrapper,
                             'rtl_files':[str(p) for p in original]+[str(path)],'io_adapter':metadata})


def verify(metadata, design):
    if design.parameters:
        raise ValueError('resolved IO wrapper does not yet support parameter overrides')
    if metadata.get('version') != 'resolved-bidirectional-wrapper-v1' or metadata['wrapper_top'] != design.top:
        raise ValueError('invalid resolved IO adapter provenance')
    path=Path(metadata['wrapper_file'])
    source,mapping=render(metadata['original_top'],design.top,metadata['original_ports'])
    originals=metadata['original_source_sha256']
    if (path.read_text() != source or digest(path) != metadata['wrapper_sha256'] or
            mapping != metadata['ports'] or
            set(map(str,design.sources)) != set(originals)|{str(path)} or
            any(digest(Path(p)) != sha for p,sha in originals.items())):
        raise ValueError('resolved IO wrapper or original RTL changed')
    return metadata['original_top']


if __name__=='__main__': main()
