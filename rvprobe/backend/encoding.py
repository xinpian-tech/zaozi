"""RVProbe known-state candidate solver; original native replay remains mandatory."""
import hashlib
import json
import re
import subprocess
import shlex
import random
import time
from pathlib import Path

from .records import save, utc
from .cover import COVER, select_cover, tcl_word
from .process import run
from .past import lower_past
from .initialization import BOOT, GLOBAL_CLOCK, global_ff_commands, materialize_initializers, expose_collision_guards, preserve_single_driver_masks, canonicalize_output_aliases, remap_encoded_expression, audit_encoded_goal_rails
from .initialization import pack_output_bits, bind_packed_expression
from dataclasses import dataclass


def terminal_result(log):
    """Never confuse an inconsistent auxiliary model with an unreachable intent."""
    statuses = re.findall(r'^ENCODED_GOAL (\w+)$', log, re.M)
    if len(statuses) != 1:
        raise ValueError('missing or ambiguous terminal encoding result')
    solver_status = statuses[0]
    inconsistent = bool(re.search(r'WAS006|The task is inconsistent|^overconstrained\s*$', log, re.M))
    if inconsistent and solver_status == 'covered':
        raise ValueError('contradictory auxiliary encoding result')
    timed_out = solver_status == 'undetermined' and bool(re.search(
        r'terminated because of a time_limit expiration|^time_limit\s*$', log, re.M))
    return dict(status='inconsistent_environment' if inconsistent else solver_status,
                solver_status=solver_status, environment_conflict_detected=inconsistent,
                termination_reason='solver_time_limit' if timed_out else
                    ('inconsistent_environment' if inconsistent else solver_status))


def resampling_commands(time_limit, *, diversity=False):
    """An unsuccessful trace search is an outcome, not a Tcl exception."""
    return [f'set replot_status [visualize -replot -force -silent -proof_time {time_limit}]',
            'puts "ENCODED_TRACE_RESULT $replot_status"',
            *(['puts "ENCODED_DIVERSITY_RESULT $replot_status"'] if diversity else []),
            'if {$replot_status != "covered"} { exit }']


def trace_result(log, *, resampling=False, diversity=False):
    terminal = terminal_result(log)
    traces = re.findall(r'^ENCODED_TRACE_RESULT ([^\r\n]+)$', log, re.M)
    expected = resampling and terminal['status'] == 'covered'
    if len(traces) != int(expected):
        raise ValueError('missing, unexpected or ambiguous encoded trace result')
    if expected:
        terminal['trace_status'] = traces[0]
        if diversity:
            terminal['diversity_status'] = traces[0]
        if traces[0] != 'covered':
            terminal.update(status='no_distinct_candidate' if diversity else 'resampling_exhausted',
                            termination_reason='trace_resampling_' + traces[0])
    return terminal


def preference_ports(design, config):
    """Only vary IO that the frozen environment does not fix to a constant."""
    fixed = set(config.get('environment', {}).get('static', {}))
    return [p for p in design.data_ports
            if p.direction == 'input' and p.kind != 'clock' and p.name not in fixed]


def distinct_input_constraint(traces, ports, cycles, seed):
    """A bounded candidate subset: one sampled IO must differ from every prior trace."""
    if not traces or any(len(trace) != cycles for trace in traces):
        raise ValueError('distinct-input traces must share the proved horizon')
    cells = [(cycle, p) for cycle in range(cycles) for p in ports]
    random.Random(f'input-distinct-v1:{seed}').shuffle(cells)
    for cycle, port in cells:
        if not re.fullmatch(r'[A-Za-z_]\w*', port.name):
            raise ValueError('distinct-input constraint requires simple port names')
        values = sorted({int(trace[cycle][port.name]) for trace in traces})
        if any(v < 0 or v >= 1 << port.width for v in values):
            raise ValueError('prior trace value outside IO width')
        if len(values) == 1 << port.width:
            continue
        expression = ' && '.join(f"{port.name} != {port.width}'h{v:x}" for v in values)
        return dict(expression=expression, cycle=cycle+1, port=port.name,
                    excluded_values=values, policy='single-cell-candidate-subset-v1')
    raise ValueError('no single sampled input can distinguish all prior traces')


def leaves(expression, atoms):
    """Recognize a deliberately restricted sequence grammar; reject everything else."""
    s = expression.strip()
    if not s:
        return ''
    # Keep native repetition topology (including empty/unbounded matches).
    # Only the Boolean leaves are encoded, never approximate n repeats by a delay.
    repetition = r'\[\*(?:\d+(?::(?:\d+|\$))?)?\]|\[\+\]|\[->\d+(?::\d+)?\]'
    checked = re.sub(repetition, '', s)
    if re.search(r'\$|\b(?:and|or|not|intersect|throughout|within|until|s_until|eventually|s_eventually|always|s_always|nexttime|s_nexttime|first_match|disable|iff)\b|\|[-=]>|\[\*|\[->|\[=', checked):
        raise ValueError('diagnostic encoder does not support this temporal form')
    depth = 0
    cuts = []
    i = 0
    while i < len(s):
        if s[i] == '(':
            depth += 1
        elif s[i] == ')':
            depth -= 1
            if depth < 0:
                raise ValueError('unbalanced sequence')
        elif depth == 0 and s[i:i+2] == '##':
            delay = re.match(r'##(?:\d+|\[\d+:\d+\])', s[i:])
            if not delay:
                raise ValueError('unsupported delay')
            cuts.append((i, i + len(delay[0])))
            i += len(delay[0]) - 1
        i += 1
    if depth:
        raise ValueError('unbalanced sequence')
    if cuts:
        parts = []
        offset = 0
        for a, b in cuts:
            parts += [leaves(s[offset:a], atoms), s[a:b]]
            offset = b
        parts += [leaves(s[offset:], atoms)]
        return ' '.join(parts)
    clock = re.match(r'@\(posedge (\w+)\)\s*', s)
    if clock:
        return clock[0] + leaves(s[clock.end():], atoms)
    repeat = re.search(r'(' + repetition + r')$', s)
    if repeat:
        operand = s[:repeat.start()].strip()
        if not operand:
            raise ValueError('repetition requires a sequence operand')
        bounds = re.fullmatch(r'\[\*(\d+):(\d+)\]', repeat[0])
        if bounds and int(bounds[1]) > int(bounds[2]):
            raise ValueError('invalid repetition bounds')
        goto = re.fullmatch(r'\[->(\d+)(?::(\d+))?\]', repeat[0])
        if goto:
            low,high=int(goto[1]),int(goto[2] or goto[1])
            if low>high:
                raise ValueError('invalid goto repetition bounds')
            positive=leaves(operand,atoms)
            # Native goto counts only true observations; X does not count as
            # a hit, but can be skipped. Keep the native operator rather than
            # expanding it into explicit !predicate sequences (different X behavior).
            atom=re.fullmatch(r'\(*rvp_encoded_atom_(\d+)_d && !rvp_encoded_atom_\1_x\)*', positive)
            if not atom:
                raise ValueError('goto repetition requires a Boolean operand')
            return f'({positive}){repeat[0]}'
        return '(' + leaves(operand, atoms) + ')' + repeat[0]
    if s.startswith('('):
        depth = 0
        for i, char in enumerate(s):
            depth += (char == '(') - (char == ')')
            if depth == 0:
                break
        if i == len(s) - 1:
            return '(' + leaves(s[1:-1], atoms) + ')'
    if '@' in s or '##' in s or ';' in s:
        raise ValueError('unsupported composite sequence')
    if '[' in s and re.search(r'\[\s*[*+]', s):
        raise ValueError('unsupported repetition placement')
    # The same predicate at different times must use the SAME knownness rail.
    # Duplicate output aliases can otherwise decode X and re-enter 2-state FPV.
    if s not in atoms:
        atoms.append(s)
    name = f'rvp_encoded_atom_{atoms.index(s)}'
    return f'({name}_d && !{name}_x)'



@dataclass(frozen=True)
class EncodingOptions:
    out: Path
    yosys: Path
    eda_shell: Path
    label: str
    project: Path | None = None
    jg_time_limit: str = '120s'
    engine_mode: str = 'auto'
    trace_cycles: int | None = None
    trace_preference: str | None = None
    trace_seed: int | None = None
    avoid_stimulus: tuple[Path, ...] = ()
    noncontending_tristates: bool = False

    def __post_init__(self):
        if not re.fullmatch(r'[1-9][0-9]*s', self.jg_time_limit):
            raise ValueError('invalid JG time limit')
        if self.engine_mode not in ('auto','Mp','Ht','B','G2'):
            raise ValueError('unsupported diagnostic engine mode')
        if self.trace_cycles is not None and not 1 <= self.trace_cycles <= 10000:
            raise ValueError('trace horizon must be between 1 and 10000')
        if self.avoid_stimulus and (not self.trace_cycles or self.trace_seed is None):
            raise ValueError('distinct input sampling requires an explicit horizon and seed')
        if self.trace_preference not in (None, 'zero', 'ones'):
            raise ValueError('invalid trace preference')


def solve(design, config, job, goal, environment_terms, args: EncodingOptions):
    """Solve a known-state candidate of a frozen job, never an acceptance verdict.

    The caller verifies file provenance and the frozen reset/environment contract.
    The backend preserves the given goal and reset, and records limitations.
    Native validation is mandatory before any resulting stimulus is admitted.
    """
    if goal['label'] != args.label or args.label not in job['labels']:
        raise ValueError('candidate goal differs from frozen job')
    began = time.monotonic()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    source_boundary = config['environment'].get('boundary', 'shared-environment-conformance-v1')
    code = re.sub(r'/\*.*?\*/|//[^\n]*', '', select_cover(Path(job['sv']).read_text(), job['labels'], args.label), flags=re.S)
    code, past_lowering = lower_past(code, {c['port'] for c in job['clocks']})
    save(out/'past-lowering.json', dict(auxiliary_only=True, original_sva_modified=False,
        histories=past_lowering, prehistory='conservative unknown; not native default equivalence'))
    cover = list(COVER.finditer(code))
    if len(cover) != 1:
        raise ValueError('expected one frozen cover')
    atoms = []
    strict = leaves(cover[0][3], atoms)
    # Encode the frozen environment expressions too: output ports are now
    # value/mask pairs. Keeping old Tcl names loses their binding; substituting
    # only the value rail could wrongly accept an unknown bus response.
    environment = [leaves(term, atoms) for term in environment_terms]
    body = COVER.sub('', code)
    header = re.search(r'\bmodule\s+\w+\s*\((.*?)\);', body, re.S)
    body = body[:header.end()-2] + ', ' + ', '.join(f'output rvp_encoded_atom_{i}' for i in range(len(atoms))) + ');' + body[header.end():]
    body = body.replace('endmodule', '\n'.join(f'assign rvp_encoded_atom_{i} = |({atom});' for i, atom in enumerate(atoms))+'\nendmodule')
    save(out/'identity.json', dict(diagnostic_only=True, remote_llm_requests=0,
        implementation_sha256={p.name:hashlib.sha256(p.read_bytes()).hexdigest()
            for p in (Path(__file__), Path(__file__).with_name('initialization.py'),
                      Path(__file__).with_name('past.py'))},
        started_utc=utc(), source_job=job['fingerprint'], original_goal=goal,
        trace_cycles=args.trace_cycles, trace_preference=args.trace_preference,
        trace_seed=args.trace_seed,
        engine_mode=args.engine_mode, solve_time_limit=args.jg_time_limit,
        preference_policy='variable-input-soft-preferences-v2',
        preference_inputs=[p.name for p in preference_ports(design, config)],
        noncontending_tristates=args.noncontending_tristates,
        boundary=config['environment'].get('boundary', source_boundary), source_boundary=source_boundary,
        atoms=atoms, strict_expression=strict, encoded_environment=environment,
        limitations=['async2sync reset approximation', 'Z-to-X normalization', 'restricted finite sequence grammar',
                     'past prehistory conservatively unknown; native defaults may differ before sufficient samples']
        + (['candidate subset excludes all simultaneous tri-state drivers; no original-unreachability conclusion']
           if args.noncontending_tristates else ['single-driver circuits only'])))
    (out/'without-cover.sv').write_text(body)
    def quote(path):
        text = str(path)
        if any(c in text for c in '\n\r"\\'):
            raise ValueError('unsupported yosys path')
        return '"'+text+'"'
    reads = ['read_verilog -sv -defer -nosynthesis ' + ' '.join('-I'+quote(p) for p in job['includeDirs']) + ' ' + ' '.join(quote(p) for p in [*job['rtl'], out/'without-cover.sv'])]
    # Preserve procedural if/case semantics: if(X) takes the else branch,
    # unlike a ternary mux. Default synthesis lowering loses this distinction.
    commands = reads + [f'hierarchy -check -top {job["top"]}', 'proc -noopt -ifx', 'flatten', 'memory_map']
    if args.noncontending_tristates:
        # Never rewrite an assertion from original RTL. Only the new, structural
        # collision checks generated by tribuf may constrain this candidate set.
        # tribuf groups by the complete output SigSpec, not overlapping bits.
        # A vector DUT driver and per-bit wrapper drivers must be normalized
        # before merging; otherwise each looks like a lone driver and its
        # enable is discarded, incorrectly shorting the DUT to external inputs.
        commands += ['select -assert-none t:$assert t:$check',
                     'tribuf', 'simplemap t:$tribuf', 'write_json '+quote(out/'tristates.json')]
        (out/'tristates.ys').write_text('\n'.join(commands)+'\n')
        with (out/'tristates.log').open('w') as log:
            run([str(args.yosys), '-Q', '-T', '-s', str(out/'tristates.ys')],
                stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
        mapped, singles = preserve_single_driver_masks(json.loads((out/'tristates.json').read_text()), job['top'])
        save(out/'tristates-masked.json', mapped)
        save(out/'single-driver-masks.json', dict(preserved=singles, semantics='inactive lone-driver Z is unknown, not data'))
        commands = ['read_json '+quote(out/'tristates-masked.json'), 'tribuf -formal', 'chformal -assert2assume']
    commands += ['setundef -undriven -undef', 'opt_expr -keepdc',
        # Width/sign extension is a pure wiring operation; lower only $pos,
        # leaving X-sensitive arithmetic and mux cells for xprop itself.
        'simplemap t:$pos', 'async2sync', 'dffunmap',
        'write_json '+quote(out/'preclock.json')]
    (out/'encode.ys').write_text('\n'.join(commands)+'\n')
    with (out/'yosys.log').open('w') as log:
        run([str(args.yosys), '-Q', '-T', '-s', str(out/'encode.ys')], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
    clock_mapping, global_cells, mapped = global_ff_commands(json.loads((out/'preclock.json').read_text()), job['top'])
    mapped, collision_guards = expose_collision_guards(mapped, job['top'])
    mapped, output_aliases = canonicalize_output_aliases(mapped, job['top'])
    strict = remap_encoded_expression(strict, output_aliases)
    environment = [remap_encoded_expression(term, output_aliases) for term in environment]
    collision_guards = list(dict.fromkeys(output_aliases.get(name, name) for name in collision_guards))
    save(out/'output-aliases.json', dict(policy='canonical-value-knownness-rails-v1', aliases=output_aliases))
    save(out/'collision-guards.json', dict(guards=collision_guards,
        scope='post-reset candidate subset only; original native replay required'))
    mapped, output_bindings = pack_output_bits(mapped, job['top'])
    strict = bind_packed_expression(strict, output_bindings)
    environment = [bind_packed_expression(term, output_bindings) for term in environment]
    collision_expressions = [bind_packed_expression(f'{name}_d && !{name}_x', output_bindings)
                             for name in collision_guards]
    save(out/'output-bindings.json', dict(policy='unique-output-bits-v1', bindings=output_bindings,
        strict_expression=strict, environment=environment, collision_expressions=collision_expressions))
    save(out/'clocked.json', mapped)
    encoding_clocks = list(job['clocks'])
    if global_cells:
        encoding_clocks.append({'port': GLOBAL_CLOCK, 'factor': 1})
    save(out/'clock-mapping.json', dict(global_ff_cells=global_cells, clocks=encoding_clocks,
        semantics='async2sync latch history sampled on the explicit global formal tick'))
    commands = ['read_json '+quote(out/'clocked.json'), *clock_mapping,
        'xprop -split-outputs -split-public -assume-def-inputs -required -formal', 'opt_clean',
        # Public output aliases can retain a value/mask decoder. JG has no
        # built-in $bwmux module; lower that exact bitwise mux before export.
        'techmap t:$bwmux', 'opt_clean',
        'write_json '+quote(out/'encoded-rails.json'),
        'write_verilog -noattr '+quote(out/'encoded.v')]
    (out/'xprop.ys').write_text('\n'.join(commands)+'\n')
    with (out/'yosys.log').open('a') as log:
        run([str(args.yosys), '-Q', '-T', '-s', str(out/'xprop.ys')], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
    save(out/'rail-audit.json', audit_encoded_goal_rails(
        json.loads((out/'encoded-rails.json').read_text()), job['top'],
        [strict, *environment, *collision_expressions]))
    # The lowered temporal topology is unchanged; only Boolean atom truth is
    # represented by the built-in value/mask encoding. Not registered as an
    # ordinary soft-input witness in production provenance.
    transformed = (out/'encoded.v').read_text()
    if transformed.count('endmodule') != 1:
        raise ValueError('expected one flattened formal model')
    if not job.get('resetSequence') or job.get('initialState'):
        raise ValueError('probe requires a frozen event reset sequence')
    transformed, reset_sequence, initialization = materialize_initializers(
        transformed, job['resetSequence'], encoding_clocks)
    transformed = transformed.replace('endmodule', f'encoded_goal: cover property ({strict});\nendmodule')
    (out/'encoded-goal.sv').write_text(transformed)
    save(out/'initialization.json', initialization)
    (out/'reset.seq').write_text(reset_sequence)
    commands = ['clear -all', f'set_property_compile_time_limit {args.jg_time_limit}',
        f'set_task_compile_time_limit {args.jg_time_limit}', 'analyze -sv12 '+tcl_word(out/'encoded-goal.sv'),
        f'elaborate -disable_auto_bbox -top {job["top"]}']
    commands += [f'clock {c["port"]} -factor {c["factor"]}' for c in encoding_clocks]
    commands += ['clock -rate -default clock', 'reset -sequence '+tcl_word(out/'reset.seq')]
    # Task-only: boot is high during initialization, low throughout proof.
    # -env would also constrain reset analysis and contradict the boot prefix.
    commands += [f'assume {{{BOOT} == 1\'b0}}']
    commands += [f'assume {{{term}}}' for term in collision_expressions]
    commands += [f'assume -env {{{term}}}' for term in environment]
    commands += [f'set_engine_mode {args.engine_mode}', f'set_prove_time_limit {args.jg_time_limit}', f'prove -property {job["top"]}.encoded_goal',
        f'puts "ENCODED_GOAL [get_property_info {job["top"]}.encoded_goal -list status]"',
        f'if {{[get_property_info {job["top"]}.encoded_goal -list status] == "covered"}} {{',
        'set_trace_optimization standard', f'visualize -cover -property {job["top"]}.encoded_goal']
    if args.trace_cycles:
        commands += [f'visualize -min_length {args.trace_cycles}', f'visualize -max_length {args.trace_cycles}']
    if args.trace_preference:
        for port in preference_ports(design, config):
            value = (1 << port.width)-1 if args.trace_preference == 'ones' else 0
            commands += [f"visualize -force -soft {{{port.name} == {port.width}'h{value:x}}} {{1:$}}"]
    if args.trace_seed is not None:
        if not args.trace_cycles:
            raise ValueError('seeded resampling requires an explicit fixed horizon')
        drives = preference_ports(design, config)
        rng = random.Random(f'encoded-candidate-v1:{args.trace_seed}:{args.label}')
        size = len(drives) * args.trace_cycles
        for cell in sorted(rng.sample(range(size), min(size, 64))):
            cycle, port = divmod(cell, len(drives))
            p = drives[port]
            commands += [f"visualize -force -soft {{{p.name} == {p.width}'h{rng.getrandbits(p.width):x}}} {cycle+1}"]
    if args.avoid_stimulus:
        distinct = distinct_input_constraint([json.loads(p.read_text()) for p in args.avoid_stimulus],
            preference_ports(design, config), args.trace_cycles, args.trace_seed)
        save(out/'input-distinct.json', {**distinct, 'prior_inputs':[
            {'path':str(p.resolve()),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
            for p in args.avoid_stimulus], 'original_goal_proven':False})
        commands += [f"visualize -force {{{distinct['expression']}}} {distinct['cycle']} -name rvp_distinct_input"]
    resampling = bool(args.trace_cycles or args.trace_preference or args.trace_seed is not None)
    if resampling:
        commands += resampling_commands(args.jg_time_limit, diversity=bool(args.avoid_stimulus))
    commands += ['visualize -save -vcd '+tcl_word(out/'witness.vcd'),
                 'visualize -save -config_only '+tcl_word(out/'witness.config.tcl'), '}', 'exit']
    (out/'solve.tcl').write_text('\n'.join(commands)+'\n')
    with (out/'jg.log').open('w') as log:
        run([str(args.eda_shell), '-c', shlex.join(['jg', '-batch', '-tcl', str(out/'solve.tcl'), '-proj', str(args.project or out/'jgproject')])], stdout=log, stderr=subprocess.STDOUT, check=True,
            timeout=int(args.jg_time_limit[:-1]) + 900)
    terminal = trace_result((out/'jg.log').read_text(), resampling=resampling,
                            diversity=bool(args.avoid_stimulus))
    summary = dict(diagnostic_only=True, remote_llm_requests=0,
        solve_time_limit=args.jg_time_limit, engine_mode=args.engine_mode,
        source_job=job['fingerprint'], label=args.label, **terminal,
        elapsed_seconds=time.monotonic()-began, finished_utc=utc(),
        original_goal_proven=False, native_replay_required=True)
    save(out/'summary.json', summary)
    return summary
