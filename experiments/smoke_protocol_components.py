"""Generic MII and Wishbone component regressions, not benchmark experiments."""
import argparse
import json
from pathlib import Path
import shlex
import sys
import time
from jinja2 import Template
from process_runner import run
from run_records import save, utc
from cycle_replay import digest

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ('haven-root','out','eda-shell'): parser.add_argument('--'+key,type=Path,required=True)
    parser.add_argument('--protocol',choices=('mii_phy','wishbone_driver','wishbone_slave','spi_slave','sdram_model'),required=True)
    parser.add_argument('--wait-states',type=int,default=0)
    parser.add_argument('--address-lsb',type=int,default=0)
    args=parser.parse_args()
    if not 0 <= args.address_lsb < 8: parser.error('address-lsb must be in 0..7')
    if not 0 <= args.wait_states <= 16: parser.error('wait-states must be in 0..16')
    out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
    root=args.haven_root.resolve()/'src/haven'
    bench=Path(__file__).parent/'tests/fixtures'/(args.protocol+'_protocol_tb.sv')
    if args.protocol in ('mii_phy','wishbone_slave','spi_slave','sdram_model'):
        template=root/'templates/bfm'/('bfm_'+args.protocol+'.sv.j2')
        params = {'wait_states':args.wait_states,'mem_depth':5,'addr_width':12} if args.protocol=='wishbone_slave' else {}
        if args.protocol == 'spi_slave': params = {'ss_width':8}
        if args.protocol == 'sdram_model': params = {'cas_latency':3}
        (out/'bfm.sv').write_text(Template(template.read_text()).render(params=params,signals={'sel_i':'sel'}))
        sources=['bfm.sv']
    else:
        sys.path.insert(0,str(root.parent))
        from haven.utils.protocol_driver_renderer import ProtocolDriverRenderer
        template=root/'templates/drivers/driver_wishbone_master.sv.j2'
        bp={'module_name':'probe','clock':{'port':'clk'},'reset':{'name':'rst','level':'high'},
            'protocol_flows':{'bus_field_mapping':{k:k for k in ('addr','data','we','read_data','cyc','stb','ack','err','rty','sel')}},
            'io_specification':{'inputs':[{'name':n,'width':8 if n in ('addr','data') else 1} for n in ('addr','data','we','cyc','stb','sel')],
                                'outputs':[{'name':n,'width':8 if n=='read_data' else 1} for n in ('read_data','ack','err','rty')]}}
        bp['io_specification']['inputs'][0].update(width=8-args.address_lsb,low_bit=args.address_lsb,high_bit=7)
        (out/'driver.sv').write_text(ProtocolDriverRenderer().render_driver(bp))
        sources=[]
    record=dict(status='running',started_utc=utc(),new_llm_tokens=0,protocol=args.protocol,
                template_sha256=digest(template),bench_sha256=digest(bench),wait_states=args.wait_states,
                address_lsb=2 if args.protocol=='wishbone_slave' else args.address_lsb)
    start=time.monotonic()
    try:
        cmds=[('compile',['vcs','-full64','-sverilog','-ntb_opts','uvm-1.2','+incdir+.',
                         '-timescale=1ns/1ps',f'+define+ADDRESS_LSB={args.address_lsb}',*sources,str(bench.resolve()),'-top',args.protocol+'_protocol_tb','-o','simv']),('sim',['./simv'])]
        for name,cmd in cmds:
            with (out/(name+'.log')).open('w') as log:
                run([str(args.eda_shell.resolve()),'-c',shlex.join(cmd)],cwd=out,stdout=log,stderr=-2,check=True,timeout=300)
        if args.protocol.upper()+'_PROTOCOL_PASS' not in (out/'sim.log').read_text():raise ValueError('missing pass marker')
        if args.protocol == 'wishbone_slave':
            for case in ('out_of_range','unaligned','unknown','address_truncation','raw_to_native_unknown'):
                with (out/(case+'.log')).open('w') as log:
                    run([str(args.eda_shell.resolve()),'-c','./simv +'+case],cwd=out,stdout=log,stderr=-2,check=False,timeout=30)
                negative = (out/(case+'.log')).read_text()
                if 'MEMORY_ADDRESS:' not in negative or 'WISHBONE_SLAVE_PROTOCOL_PASS' in negative:
                    raise ValueError('invalid memory address was not rejected: '+case)
            record['invalid_addresses_rejected'] = True
        record['status']='passed'
    except Exception as error:record.update(status='failed',error=str(error))
    record.update(finished_utc=utc(),elapsed_seconds=time.monotonic()-start)
    save(out/'summary.json',record);print(json.dumps(record))
    return int(record['status']!='passed')

if __name__=='__main__':raise SystemExit(main())
