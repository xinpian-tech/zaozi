"""JG integration regression: declaration init alone must not be trusted."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess

from encoded_initialization import BOOT, materialize_initializers
from process_runner import run
from run_records import save

CODE = """module init_probe(clock, reset, we, data);
input clock, reset, we, data;
reg mask = 1'h1;
reg value;
reg written;
always @(posedge clock)
  written <= reset ? 1'b0 : (we ? 1'b1 : written);
always @(posedge clock)
  mask <= we ? 1'b0 : mask;
always @(posedge clock)
  value <= we ? data : value;
bad: cover property (@(posedge clock) !written && !mask);
good: cover property (@(posedge clock) written && !mask);
endmodule
"""
RESET = "reset 1'b1\nwe 1'b0\ndata 1'b0\n10\nreset 1'b0\n$\n"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out', type=Path, required=True)
    args = p.parse_args()
    args.out.mkdir(exist_ok=False)
    results = {}
    for name in ('declaration_only', 'explicit_boot'):
        out = (args.out/name).resolve()
        out.mkdir()
        code, seq = CODE, RESET
        assumption = ''
        if name == 'explicit_boot':
            code, seq, info = materialize_initializers(code, seq, [{'factor': 1}])
            save(out/'initialization.json', info)
            assumption = f"assume {{{BOOT} == 1'b0}}\n"
        (out/'probe.sv').write_text(code)
        (out/'reset.seq').write_text(seq)
        (out/'solve.tcl').write_text(
            f'clear -all\nanalyze -sv12 {{{out}/probe.sv}}\nelaborate -top init_probe\n'
            f'clock clock\nreset -sequence {{{out}/reset.seq}}\n{assumption}'
            f'get_reset_info -save_values {{{out}/reset-values.txt}} -all\n'
            'set_prove_time_limit 20s\nprove -all\n'
            'puts "BAD [get_property_info init_probe.bad -list status]"\n'
            'puts "GOOD [get_property_info init_probe.good -list status]"\nexit\n')
        with (out/'jg.log').open('w') as log:
            run([str(Path(__file__).parent/'eda-shell'), '-c', shlex.join([
                'jg', '-batch', '-tcl', str(out/'solve.tcl'), '-proj', str(out/'jgproject')])],
                stdout=log, stderr=subprocess.STDOUT, timeout=300, check=True)
        results[name] = dict(re.findall(r'^(BAD|GOOD) (\w+)$', (out/'jg.log').read_text(), re.M))
    passed = (results['explicit_boot'] == {'BAD': 'unreachable', 'GOOD': 'covered'})
    save(args.out/'summary.json', dict(status='passed' if passed else 'failed',
                                     diagnostic_only=True, remote_llm_requests=0, results=results))
    print(json.dumps(results))
    return int(not passed)


if __name__ == '__main__':
    raise SystemExit(main())
