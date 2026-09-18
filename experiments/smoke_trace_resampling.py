"""Synthetic JG trace-exhaustion regression, never a model prompt example."""
import argparse
from pathlib import Path
import shlex
import subprocess

import backend_imports
from rvprobe.backend.encoding import resampling_commands, trace_result
from rvprobe.backend.process import run
from run_records import save


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve(); out.mkdir(parents=True, exist_ok=False)
    (out/'dut.sv').write_text('module dut(input clock, input p);\n'
        'encoded_goal: cover property (@(posedge clock) p);\nendmodule\n')
    record = dict(status='running', diagnostic_only=True, remote_llm_requests=0, cases={})
    try:
        for name, value in [('covered', 1), ('exhausted', 0)]:
            directory = out/name; directory.mkdir()
            commands = ['clear -all', 'analyze -sv12 '+str(out/'dut.sv'),
                'elaborate -top dut', 'clock clock', 'reset -none',
                'set_prove_time_limit 5s', 'prove -property dut.encoded_goal',
                'puts "ENCODED_GOAL [get_property_info dut.encoded_goal -list status]"',
                'visualize -cover -property dut.encoded_goal',
                f"visualize -force {{p == 1'b{value}}} {{1:$}}",
                *resampling_commands('5s'),
                'visualize -save -vcd '+str(directory/'witness.vcd'), 'exit']
            (directory/'solve.tcl').write_text('\n'.join(commands)+'\n')
            with (directory/'jg.log').open('w') as log:
                run([str(Path(__file__).resolve().parent/'eda-shell'), '-c',
                    shlex.join(['jg','-batch','-tcl',str(directory/'solve.tcl'),
                                '-proj',str(directory/'project')])], stdout=log,
                    stderr=subprocess.STDOUT, check=True, timeout=120)
            result = trace_result((directory/'jg.log').read_text(), resampling=True)
            expected = 'covered' if value else 'resampling_exhausted'
            if result['status'] != expected or (directory/'witness.vcd').exists() != bool(value):
                raise ValueError('wrong trace outcome or an exhausted trace was exported')
            record['cases'][name] = result
        record['status'] = 'passed'
    except Exception as error:
        record.update(status='failed', error=str(error))
        raise
    finally:
        save(out/'summary.json', record)


if __name__ == '__main__': main()
