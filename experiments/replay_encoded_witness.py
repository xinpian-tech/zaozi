"""Native acceptance check for an isolated alternative-encoding diagnostic."""
import backend_imports
import argparse
import json
import time
from pathlib import Path
from coverage_flow import load_haven, prepare, HavenSimulation
from cycle_replay import witness_frames
from haven_shared import render_witness_sequence
from rvprobe.backend.replay import attach
from rvprobe.backend.runtime import ReplayTransport, WitnessBackend
from offline_validation import no_model_calls
from run_records import save, utc
from witness_sampling import frozen_inputs, import_sample
from encoded_witness_probe import diagnostic_config


def main():
    parser = argparse.ArgumentParser()
    for key in ('source-solve', 'replay-config', 'encoded', 'stage1', 'haven-root', 'out'):
        parser.add_argument('--'+key, type=Path, required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--negative-drive')
    parser.add_argument('--zero-delays', action='store_true', help='diagnostic only; compare simulator delay effects')
    parser.add_argument('--boundary', choices=('independent-dut-v1', 'frozen-source'),
                        default='independent-dut-v1')
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(exist_ok=False)
    began = time.monotonic()
    record = dict(diagnostic_only=True, remote_llm_requests=0, started_utc=utc(),
                  label=args.label, encoded_source=str(args.encoded), status='running',
                  negative_drive=args.negative_drive, zero_delays=args.zero_delays)
    load_haven(args.haven_root)
    try:
        with no_model_calls():
            design, config, job, goals = frozen_inputs(args.source_solve, args.replay_config)
            config = diagnostic_config(config, args.replay_config, args.boundary)
            replay_path = out/'replay.json'
            save(replay_path, config)
            identity = json.loads((args.encoded/'identity.json').read_text())
            record.update(boundary=config['environment'].get('boundary', 'shared-environment-conformance-v1'),
                          encoded_boundary=identity.get('boundary', 'shared-environment-conformance-v1'))
            original = next(g for g in goals if g['label'] == args.label)
            sampled = import_sample(args.encoded/'witness.vcd', args.label, design, event_mode=True)
            sampled['origin'] = 'diagnostic-yosys-xprop-async2sync'
            candidate = {k: original[k] for k in ('generationLabel', 'utModule', 'utSourceSha256', 'fingerprint', 'engine')}
            candidate.update(sampled)
            record.update(original_fingerprint=job['fingerprint'], candidate=candidate)
            rows = witness_frames(design, config, candidate, 0)
            attach(rows, original)
            if args.negative_drive:
                port, value = args.negative_drive.split('=', 1)
                widths = {p.name:p.width for p in design.data_ports if p.direction == 'input' and p.kind != 'clock'}
                value = int(value, 0)
                if port not in widths or not 0 <= value < 2**widths[port]:
                    raise ValueError('invalid negative mutation')
                for row in rows:
                    if row['kind'] == 'witness':
                        row['drive'][port] = value
            sequence = render_witness_sequence(design, rows, 'rvp_encoded_probe', 0)
            bundle = prepare(args.stage1, args.haven_root, replay_path, out/'shared')
            bundle['components']['top'] = bundle['components']['top'].replace('endmodule', 'initial begin $dumpfile("dut.vcd"); $dumpvars(0, u_dut); end\nendmodule')
            eda = json.loads((Path(__file__).parent/'designs/haven_eda.json').read_text())
            eda['eda_env'] = {'shell':str(Path(__file__).resolve().parent/'eda-shell')}
            if args.zero_delays:
                eda['eda_tools']['vcs']['flags'].append('+delay_mode_zero')
            simulator = HavenSimulation(bundle, design, eda, 20260906)
            if args.negative_drive:
                result = simulator(out/'sequence-0', [sequence], rows)
            else:
                measured = []
                def measure(source, frames):
                    value = simulator(out/f'sequence-{len(measured)}', [source], frames)
                    measured.append(value)
                    return value['replay']
                backend = WitnessBackend(ReplayTransport(
                    frames=lambda row, segment: witness_frames(design, config, row, segment),
                    render=lambda frames, name, ordinal: render_witness_sequence(design, frames, name, ordinal),
                    measure=measure), replenish=lambda *args: [])
                produced = backend.generate([{**original, 'sequences':[candidate]}],
                    out/'backend', 'rvp_encoded_probe', 1)
                record['backend'] = produced['metadata']
                if not produced['sequences']:
                    raise ValueError('encoded diagnostic candidate did not satisfy the original native LTL')
                result = measured[0]
            record.update(status='passed', coverage=result)
            if args.negative_drive:
                record.update(status='failed', error='negative stimulus was incorrectly accepted')
    except Exception as error:
        record.update(status='failed', error=str(error), diagnostics=getattr(error, 'diagnostics', {}))
        if args.negative_drive and record['diagnostics'].get('kind') == 'formal_replay_semantics_mismatch':
            record['status'] = 'rejected_as_expected'
    record.update(finished_utc=utc(), elapsed_seconds=time.monotonic()-began)
    save(out/'summary.json', record)
    print(json.dumps({k:v for k,v in record.items() if k not in ('coverage','candidate','diagnostics')}))
    return int(record['status'] != ('rejected_as_expected' if args.negative_drive else 'passed'))


if __name__ == '__main__':
    raise SystemExit(main())
