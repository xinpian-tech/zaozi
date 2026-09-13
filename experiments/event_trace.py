"""Lossless multi-clock JG event export: sample state on the formal timebase.

JG reports state at formal sample k and clock transitions for that step at k+1.
Keep half-step falling edges too; primary-clock-only sampling loses those events.
"""
from fractions import Fraction
from functools import reduce
from math import gcd, lcm
from pathlib import Path
import re

CONTRACT = 'jg-multiclock-event-v1'
FORMAL_CLOCK = ':jasper_formal_clock'


def vcd_blocks(path):
    names, scope, values, blocks = {}, [], {}, []
    definitions, stamp, changes = True, None, {}
    for raw in Path(path).read_text().splitlines():
        line = raw.strip()
        words = line.split()
        if not words:
            continue
        if definitions:
            if words[0] == '$scope': scope.append(words[2])
            elif words[0] == '$upscope': scope.pop()
            elif words[0] == '$var':
                names.setdefault(words[3], []).append(('/'.join(scope[1:] + [words[4]]), int(words[2])))
            elif words[0] == '$enddefinitions': definitions = False
            continue
        if line.startswith('#'):
            if stamp is not None:
                blocks.append((stamp, dict(values), dict(changes)))
            stamp, changes = int(line[1:]), {}
            continue
        if line[0] in '$': continue
        if line[0] in 'bB': bits, symbol = line[1:].split()
        elif line[0].lower() in '01xz': bits, symbol = line[0], line[1:]
        else: continue
        for name, width in names.get(symbol, []):
            word = bits.lower().rjust(width, bits[0].lower() if bits[0].lower() in 'xz' else '0')
            value = int(''.join('1' if b == '1' else '0' for b in word), 2)
            mask = int(''.join('1' if b in '01' else '0' for b in word), 2)
            values[name] = changes[name] = (value, mask)
    if stamp is not None:
        blocks.append((stamp, dict(values), dict(changes)))
    return blocks


def validate_clocks(clocks):
    if not clocks or len({c['port'] for c in clocks}) != len(clocks):
        raise ValueError('clock schedule must have distinct ports')
    for c in clocks:
        if set(c) != {'port', 'period_ps'} or not re.fullmatch(r'[A-Za-z_]\w*', c['port']):
            raise ValueError('invalid clock declaration')
        if type(c['period_ps']) is not int or c['period_ps'] < 2 or c['period_ps'] % 2:
            raise ValueError('clock period must be a positive even number of picoseconds')
    quantum = reduce(gcd, (c['period_ps'] for c in clocks))
    if lcm(*(c['period_ps']//quantum for c in clocks)) > 10000:
        raise ValueError('clock superperiod exceeds 10000 formal steps')
    return quantum


def clock_commands(clocks, primary):
    quantum = validate_clocks(clocks)
    if primary not in {c['port'] for c in clocks}:
        raise ValueError('primary clock is not in the environment schedule')
    return [f"clock {'clock' if c['port'] == primary else c['port']} -factor {c['period_ps']//quantum}"
            for c in clocks] + ['clock -rate -default clock']


def event_frames(path, design, clocks):
    quantum = validate_clocks(clocks)
    clock_names = {('clock' if c['port'] == design.clock else c['port']): c['port'] for c in clocks}
    blocks = vcd_blocks(path)
    samples = [(t, state) for t, state, changes in blocks if changes.get(FORMAL_CLOCK) == (1, 1)]
    if not samples:
        raise ValueError('missing formal samples')
    if len(samples) >= 2:
        period = samples[1][0] - samples[0][0]
    else:
        falls = [t for t,s,c in blocks if c.get(FORMAL_CLOCK) == (0, 1)]
        if not falls: raise ValueError('missing formal clock falling edge')
        period = 2 * (falls[0]-samples[0][0])
    if period <= 0 or samples[0][0] != 0 or any(b[0]-a[0] != period for a,b in zip(samples, samples[1:])):
        raise ValueError('nonuniform formal VCD timebase')
    end = samples[-1][0]
    timeline = {t: {'sample': s, 'clocks': {}} for t,s in samples}
    previous = {}
    observed = {}
    for t,state,changes in blocks:
        for formal, actual in clock_names.items():
            if formal not in changes: continue
            value, mask = changes[formal]
            if mask != 1: raise ValueError('unknown clock in formal trace')
            old = previous.get(formal, 0)
            previous[formal] = value
            at = t - period
            if old != value and 0 <= at < end:
                timeline.setdefault(at, {'clocks': {}})['clocks'][actual] = value
                observed[(at, actual)] = value
    # The final sampling edge is not dumped by JG. Complete clocks only from
    # the declared periodic environment, verifying every earlier transition.
    # Omitting that edge replays state correctly but never samples the cover.
    for c in clocks:
        half = Fraction(period * c['period_ps'], 2 * quantum)
        if half.denominator != 1:
            raise ValueError('clock period is not representable in this VCD')
        for index, at in enumerate(range(0, end+1, int(half))):
            value = 1 - index % 2
            if at < end and observed.pop((at, c['port']), None) != value:
                raise ValueError('VCD clock waveform differs from declared clock environment')
            timeline.setdefault(at, {'clocks': {}})['clocks'][c['port']] = value
    if observed:
        raise ValueError('unexpected clock edges in VCD')
    rows, drive, levels = [], {}, {c['port']: 0 for c in clocks}
    stamps = sorted(timeline)
    for index, at in enumerate(stamps):
        entry = timeline[at]
        sample = entry.get('sample')
        expected = {}
        if sample:
            if sample.get('reset') != (0, 1): raise ValueError('trace is not entirely post-reset')
            for p in design.data_ports:
                if p.kind == 'clock': continue
                value, mask = sample.get(p.name, sample.get('dut/' + p.name, (0, 0)))
                if p.direction == 'input': drive[p.name] = value & mask
                elif mask: expected[p.name] = [value, mask]
        levels.update(entry['clocks'])
        after = stamps[index+1] if index+1 < len(stamps) else at + period
        duration = Fraction((after-at)*quantum, period)
        if duration.denominator != 1 or duration < 2:
            raise ValueError('VCD clock transitions cannot be represented at picosecond resolution')
        rows.append({'kind': 'witness', 'drive': dict(drive), 'expected': expected,
                     'clocks': dict(levels), 'duration_ps': int(duration),
                     'formal_sample': sample is not None})
    return rows


def idle_events(design, config, cycles, kind):
    clocks = config['environment']['clocks']
    periods = {c['port']: c['period_ps'] for c in clocks}
    validate_clocks(clocks)
    # End reset on a complete superperiod so the witness starts at the same
    # clock phase as JG. No clocks are tied together or sampled away.
    end = cycles * periods[design.clock]
    if kind == 'reset':
        superperiod = lcm(*periods.values())
        end = ((end + superperiod-1)//superperiod) * superperiod
    times = sorted({t for period in periods.values() for t in range(0, end, period//2)} | {end})
    drive = dict(config['idle'])
    drive.update(config['environment'].get('static', {}))
    for r in config['environment'].get('extra_resets', []):
        drive[r['port']] = int(not r['active_low']) if kind=='reset' else int(r['active_low'])
    return [{'kind': kind, 'drive': dict(drive), 'expected': {},
             'clocks': {name: 1-int(t % period >= period//2) for name,period in periods.items()},
             'duration_ps': after-t, 'formal_sample': False}
            for t,after in zip(times,times[1:])]
