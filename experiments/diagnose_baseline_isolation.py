"""Diagnostic only: run saved baseline sequences separately, without editing them.

This does not replace full baseline acceptance or qualify a paired result.
"""
import argparse
import json
from pathlib import Path
from coverage_flow import load_bundle, HavenSimulation
from run_records import save, utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('bundle','haven-root','out','eda-config','eda-shell'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--sequences',type=int,nargs='+',required=True)
    parser.add_argument('--trace',action='store_true',help='add diagnostic-only VCD observation; no drivers or checks changed')
    parser.add_argument('--observe',nargs='*',default=[],help='additional read-only hierarchical signals for trace diagnostics')
    parser.add_argument('--trace-edges',type=int,default=40)
    parser.add_argument('--trace-stride',type=int,default=1)
    args = parser.parse_args()
    if args.trace_stride < 1: parser.error('trace stride must be positive')
    bundle, design, _ = load_bundle(args.bundle,args.haven_root)
    if args.trace:
        from copy import deepcopy
        from haven_shared import replace_once
        from run_records import fingerprint
        bundle = deepcopy(bundle)
        original = bundle['fingerprint']
        labels = ' '.join(p.name+'=%h' for p in design.ports)
        values = ', '.join('u_dut.'+p.name for p in design.ports)
        import re
        if any(not re.fullmatch(r'[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+',name) for name in args.observe):
            raise ValueError('observation must be a plain hierarchical signal')
        labels += ''.join(' '+name+'=%h' for name in args.observe)
        values += ''.join(', '+name for name in args.observe)
        observation = (f'integer diagnostic_edges = 0; always @(negedge rvp_pin_{design.clock}) begin '
                       f'if (diagnostic_edges < {args.trace_edges*args.trace_stride} && diagnostic_edges % {args.trace_stride} == 0) $display("DIAGNOSTIC_IO %0t {labels}", $time, {values}); '
                       'diagnostic_edges = diagnostic_edges + 1; end\n')
        bundle['components']['top'] = replace_once(bundle['components']['top'],'endmodule',
            'initial begin $dumpfile("diagnostic.vcd"); $dumpvars(0); end\n'+observation+'endmodule')
        bundle['fingerprint'] = fingerprint({'original':original,'diagnostic_top':bundle['components']['top']})
    if any(not 1 <= i <= len(bundle['sequences']) for i in args.sequences):
        raise ValueError('sequence index outside saved baseline')
    args.out.mkdir(parents=True,exist_ok=False)
    config = json.loads(args.eda_config.read_text())
    config['eda_env'] = {'shell':str(args.eda_shell.resolve())}
    if args.trace:
        config['eda_tools']['vcs']['flags'].append('-debug_access+all')
    sim = HavenSimulation(bundle,design,config,20260906)
    record = {'status':'running','started_utc':utc(),'diagnostic_only':True,
              'new_llm_tokens':0,'model_requests_allowed':False,'results':{}}
    for index in args.sequences:
        try:
            from offline_validation import no_model_calls
            with no_model_calls():
                result = sim(args.out/f'sequence-{index}',[bundle['sequences'][index-1]],[])
            record['results'][str(index)] = {'status':'passed','coverage':result}
        except Exception as error:
            record['results'][str(index)] = {'status':'failed','error':str(error),
                                            'diagnostics':getattr(error,'diagnostics',{})}
        save(args.out/'summary.json',record)
    record.update(status='diagnostic_finished',finished_utc=utc())
    save(args.out/'summary.json',record)
    print(json.dumps({k:v['status'] for k,v in record['results'].items()}))


if __name__ == '__main__': main()
