"""Native four-state Cover monitor and frozen-goal provenance."""
import hashlib
import json
import re
from pathlib import Path

from .validation import validate
from .cover import select_cover, COVER

POLICY = 'native-io-ltl-replay-v1'


def attach(rows, goal):
    selected = Path(goal['witnessFile']).parent.parent/'selected'
    paths = list(selected.glob('*.sv'))
    if len(paths) != 1:
        raise ValueError('missing original selected UT for native LTL replay')
    source = paths[0].read_text()
    job = json.loads((selected.parent.parent/'prepared.json').read_text())
    original = Path(job['sv']).read_text()
    if (hashlib.sha256(original.encode()).hexdigest() != job['svSha256'] or
            job['sourceSha256'] != goal['utSourceSha256'] or
            job['fingerprint'] != goal['fingerprint']):
        raise ValueError('original LTL differs from the prepared solver job')
    def canonical(text):
        return re.sub(r'\s+','',re.sub(r'/\*.*?\*/|//[^\n]*','',text,flags=re.S))
    expected = select_cover(original,job['labels'],goal['generationLabel'])
    if canonical(source) != canonical(expected):
        raise ValueError('selected LTL differs from the original generated goal')
    # utModule is the author-facing Scala class. CIRCT may suffix the emitted
    # module name; only the prepared job identifies the actual SV boundary.
    if job.get('module', goal['utModule']) != goal['utModule']:
        raise ValueError('goal author module differs from the prepared solver job')
    meta = dict(policy=POLICY, source=source, top=job['top'], label=goal['generationLabel'],
                sha256=hashlib.sha256(source.encode()).hexdigest())
    rows[0]['ltl'] = meta


def monitor(meta, design):
    if set(meta) != {'policy','source','top','label','sha256'} or meta['policy'] != POLICY:
        raise ValueError('invalid native LTL provenance')
    source, top, label = meta['source'], meta['top'], meta['label']
    if hashlib.sha256(source.encode()).hexdigest() != meta['sha256']:
        raise ValueError('native LTL source changed')
    validate(source, design, top, [label])
    code = re.sub(r'/\*.*?\*/|//[^\n]*', '', source, flags=re.S)
    code = select_cover(code,[label],label)
    header = re.search(r'\bmodule\s+\w+\s*\((.*?)\);',code,re.S)
    instance = re.search(r'\b'+re.escape(design.top)+r'\s+\w+\s*\((.*?)\);',code,re.S)
    pins = dict(re.findall(r'\.(\w+)\s*\(\s*(\w+)\s*\)',instance[1]))
    body = code[header.end():instance.start()] + code[instance.end():]
    # Resolve the alias nets previously driven by the DUT to the observed IO.
    aliases = {pins[p.name]:p.name for p in design.data_ports if p.direction=='output'}
    body = re.sub(r'\b\w+\b',lambda m:aliases.get(m[0],m[0]),body)
    for port in aliases.values():
        body = re.sub(r'\bwire\s+(?:\[[^]]+\]\s*)?'+re.escape(port)+r'\s*;', '',body)
        # The original outward assignment may run through more than one alias.
        # Validation above established faithful wiring; the live input now owns
        # this port, so retain intermediate aliases but remove its old driver.
        body = re.sub(r'\bassign\s+'+re.escape(port)+r'\s*=\s*[^;]+;', '',body)
    name = 'rvp_ltl_'+meta['sha256'][:16]
    ports = ['input clock, reset, rvp_active']
    ports += [f'input [{p.width-1}:0] {p.name}' for p in design.data_ports]
    body = COVER.sub(lambda m: f'{label}: cover property (disable iff (reset || !rvp_active) {m[3]}) '
                     f'begin $display("RVPROBE_LTL_HIT {meta["sha256"]} {label}"); end',body)
    return name, f'module {name}('+', '.join(ports)+');\n'+body


def check_hit(meta, log):
    expected = f'RVPROBE_LTL_HIT {meta["sha256"]} {meta["label"]}'
    if expected not in log.splitlines():
        raise ValueError('original LTL goal did not hold on live IO: '+meta['label'])
    return {'policy':POLICY,'label':meta['label'],'source_sha256':meta['sha256'],
            'native_cover_hit':True,'four_state_simulation':True}
