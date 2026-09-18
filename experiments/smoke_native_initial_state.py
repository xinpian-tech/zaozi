"""Offline check of native JG reset-state import, with original saved LTL.

No model calls, no DUT edits, only RTL-simulated reset state, no replacement cover.
Diagnostic only; the original experiment remains immutable.
"""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time

import backend_imports
from coverage_flow import load_bundle, HavenSimulation
from cycle_replay import digest, witness_frames
from haven_shared import render_witness_sequence
from offline_validation import no_model_calls
from rtl_initial_state import generate
from rvprobe.backend.cover import tcl_word
from rvprobe.backend.process import run
from rvprobe.backend.replay import attach
from run_records import save, utc
from witness_sampling import frozen_inputs, import_sample
from witness_sampling import sample_goal
from sequence_framework import parse_response, write_sources
from sequence_experiment import harness


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('source-solve', 'replay-config', 'bundle', 'haven-root', 'out'):
        p.add_argument('--'+name, type=Path, required=True)
    p.add_argument('--label', required=True)
    p.add_argument('--initial-mode', choices=('prefix', 'cumulative', 'post-reset'), default='cumulative')
    p.add_argument('--framework', action='store_true', help='use the real harness and sampler instead of diagnostic Tcl')
    p.add_argument('--negative-drive', help='one input forced to a value; must fail original-cover replay')
    args = p.parse_args()
    out = args.out.resolve(); out.mkdir(parents=True, exist_ok=False)
    shell = Path(__file__).resolve().parent/'eda-shell'
    record = dict(status='running', diagnostic_only=True, remote_llm_requests=0, started_utc=utc())
    began = time.monotonic()
    try:
        with no_model_calls():
            design, replay, job, goals = frozen_inputs(args.source_solve, args.replay_config)
            if args.framework:
                raw = (args.source_solve.parent/'sources/model.ltl').read_text()
                source = out/'generation/sources'
                write_sources(source, design, parse_response(raw), '120s')
                report, log = harness(source, out/'generation/solve', shell, replay_config=args.replay_config)
                save(out/'generation/harness.json', report)
                (out/'generation/harness.log').write_text(log)
                if not report.get('ok'): raise ValueError('framework harness failed: '+json.dumps(report))
                design, replay, job, goals = frozen_inputs(out/'generation/solve', args.replay_config)
                goal = next(g for g in goals if g['label'] == args.label)
                if not job.get('resetSnapshotState'): raise ValueError('framework did not install native reset state')
                pool = sample_goal(job, goal, design, replay, out/'sampling', 4, 20260906, '30s', shell)
                bundle, _, _ = load_bundle(args.bundle, args.haven_root)
                eda = json.loads((Path(__file__).parent/'designs/haven_eda.json').read_text())
                eda['eda_env'] = {'shell':str(shell)}
                simulator = HavenSimulation(bundle, design, eda, 20260906)
                results = []
                for index, candidate in enumerate(pool):
                    rows = witness_frames(design, replay, candidate, 0); attach(rows, goal)
                    if args.negative_drive:
                        name, value = args.negative_drive.split('=', 1)
                        value = int(value, 0)
                        port = next(p for p in design.data_ports if p.name == name and p.direction == 'input')
                        if not 0 <= value < 1 << port.width: raise ValueError('invalid negative drive value')
                        for row in rows:
                            if row['kind'] == 'witness': row['drive'][name] = value
                    sequence = render_witness_sequence(design, rows, f'rvp_native_init_{index}', 0)
                    results.append(simulator(out/f'replay-{index}', [sequence], rows))
                record.update(status='passed', accepted_sequences=len(results), coverage=results,
                    original_ltl_sha256=digest(args.source_solve.parent/'sources/model.ltl'),
                    emitted_ltl_sha256=digest(source/'model.ltl'), reset_snapshot_record=job['resetSnapshotRecord'])
                if args.negative_drive: record.update(status='failed', error='negative mutation was accepted')
                return finish(out, record, began)
            env = replay.get('environment', {})
            if env and (len(env['clocks']) != 1 or env['clocks'][0]['port'] != design.clock or
                        any(env.get(k) for k in ('extra_resets', 'open_drain', 'feedback', 'static'))):
                raise ValueError('diagnostic requires the existing single-clock independent reset environment')
            probe_replay = {k:v for k,v in replay.items() if k != 'environment'}
            state = generate(design, probe_replay, out/'snapshot', '', shell,
                             power_on=args.initial_mode == 'prefix', native_reset=args.initial_mode != 'prefix')
            words = state.split(); values = dict(zip(words[::2], words[1::2]))
            # Only constrain known initial bits; unknown state is never zero-filled.
            values = {n:v for n,v in values.items() if not re.fullmatch(r"\d+'bx+", v)}
            save(out/'power-on-values.json', values)
            source = args.source_solve/args.label/'jg'
            reset = (source/'reset.seq').read_text()
            prefix = ''.join(f'{n} {v}\n' for n,v in sorted(values.items()))
            (out/'reset.seq').write_text((prefix if args.initial_mode == 'prefix' else '')+reset)
            (out/'initial.state').write_text(prefix)
            tcl = (source/'generate.tcl').read_text()
            # Archived files retain their original scratch alias. Rebase BOTH
            # spellings before launching any command that exports a trace.
            archived_alias = str(Path(original_path := next(g for g in goals if g['label'] == args.label)['witnessFile']).parent)
            for spelling in (str(source), archived_alias):
                tcl = tcl.replace(spelling, str(out))
            if str(Path(original_path)) in tcl or str(source/'witness.vcd') in tcl:
                raise ValueError('diagnostic still writes an archived witness')
            if args.initial_mode == 'cumulative':
                tcl = tcl.replace('reset -sequence', 'set_cumulative_reset on\nreset -init_state '+
                                  tcl_word(out/'initial.state')+'\nreset -sequence', 1)
            elif args.initial_mode == 'post-reset':
                tcl = re.sub(r'reset -sequence [^\n]+',
                    'reset reset -init_state '+tcl_word(out/'initial.state'), tcl, count=1)
            sv = source/'ModelUT.sv'
            (out/'ModelUT.sv').write_bytes(sv.read_bytes())
            record.update(original_sv_sha256=digest(sv), reset_prefix_words=len(values),
                          original_reset_sha256=digest(source/'reset.seq'))
            tcl = tcl.replace('set_prove_time_limit',
                'get_reset_info -save_values '+tcl_word(out/'reset-values.txt')+' -all\nset_prove_time_limit', 1)
            (out/'generate.tcl').write_text(tcl)
            with (out/'jg.log').open('w') as log:
                run([str(shell), '-c', shlex.join(['jg', '-batch', '-tcl', str(out/'generate.tcl'),
                    '-proj', str(out/'jgproj')])], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=420)
            log = (out/'jg.log').read_text()
            record['reset_warnings'] = [s for s in log.splitlines() if 'WARN' in s]
            if 'JGDONE' not in log or not (out/'witness.vcd').is_file():
                raise ValueError('native initialized cover did not generate a complete witness')
            original = next(g for g in goals if g['label'] == args.label)
            candidate = {**original, **import_sample(out/'witness.vcd', args.label, design, event_mode=True)}
            candidate['origin'] = 'diagnostic-native-reset-prefix'
            rows = witness_frames(design, replay, candidate, 0); attach(rows, original)
            sequence = render_witness_sequence(design, rows, 'rvp_native_init_probe', 0)
            bundle, bundle_design, _ = load_bundle(args.bundle, args.haven_root)
            if bundle_design.record() != design.record(): raise ValueError('bundle design differs')
            eda = json.loads((Path(__file__).parent/'designs/haven_eda.json').read_text())
            eda['eda_env'] = {'shell':str(shell)}
            result = HavenSimulation(bundle, design, eda, 20260906)(out/'replay', [sequence], rows)
            record.update(status='passed', replay=result, witness_sha256=digest(out/'witness.vcd'))
    except Exception as error:
        record.update(status='failed', error=str(error), diagnostics=getattr(error, 'diagnostics', {}))
        if args.negative_drive and record['diagnostics'].get('kind') == 'formal_replay_semantics_mismatch':
            record['status'] = 'rejected_as_expected'
    return finish(out, record, began)


def finish(out, record, began):
    record.update(finished_utc=utc(), elapsed_seconds=time.monotonic()-began)
    save(out/'summary.json', record)
    print(json.dumps({k:v for k,v in record.items() if k not in ('replay', 'coverage', 'diagnostics', 'reset_warnings')}))
    return int(record['status'] not in ('passed', 'rejected_as_expected'))


if __name__ == '__main__': raise SystemExit(main())
