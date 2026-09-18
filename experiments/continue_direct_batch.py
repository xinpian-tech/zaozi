"""One-shot completion of the four explicitly selected HTTP-402 cells."""
import argparse
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
import json
import os
from pathlib import Path
import sys
import time

from run_records import save, fresh_directory, utc
from batch_lifecycle import current_owner
from haven_design_batch import archive_design
from rvprobe.backend.process import run

CELLS=(('directed_stimulus','ue_uart'),('directed_sva','ue_uart'),
       ('directed_stimulus','sdram'),('directed_sva','sdram'))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('source-root','work','archive','env-file','yosys'):p.add_argument('--'+name,type=Path,required=True)
    args=p.parse_args();work=fresh_directory(args.work.resolve());archive=fresh_directory(args.archive.resolve())
    state=dict(status='running',started_utc=utc(),owner=current_owner(),
        cells={m+'/'+n:{'status':'queued'} for m,n in CELLS},jobs=2)
    def checkpoint():
        state['updated_utc']=utc();save(work/'progress.json',state);save(archive/'progress.json',state)
    def worker(method,name):
        directory=work/method/name;directory.parent.mkdir(parents=True,exist_ok=True)
        log=work/(method+'-'+name+'.log');start=time.monotonic()
        record=dict(status='running',method=method,design=name,started_utc=utc())
        try:
            env={**os.environ,'RVPROBE_STORAGE_WORK_ROOT':str(work),'RVPROBE_STORAGE_ARCHIVE_ROOT':str(archive)}
            with log.open('x') as stream:
                result=run([sys.executable,str(Path(__file__).with_name('continue_direct_402.py')),
                    '--source',str(args.source_root/method/name),'--out',str(directory),
                    '--env-file',str(args.env_file),'--yosys',str(args.yosys)],
                    env=env,stdout=stream,stderr=-2,timeout=13*3600)
            summary=json.loads((directory/'summary.json').read_text())
            record.update(status=summary['status'],error=summary.get('error'),exit_code=result.returncode)
        except Exception as error:record.update(status='failed',error=str(error))
        finally:
            record.update(finished_utc=utc(),elapsed_seconds=time.monotonic()-start)
            directory.mkdir(parents=True,exist_ok=True)
            if log.exists():
                import shutil
                shutil.copy2(log,directory/'process.log')
            try:
                archive_design(directory,archive/method/name,record,True)
                record['storage_status']='relocated'
            except Exception as error:record['archive_error']=str(error)
        return record
    pending=list(CELLS);active={};payment_block=False
    checkpoint()
    with ThreadPoolExecutor(max_workers=2) as pool:
        while pending or active:
            while pending and len(active)<2 and not payment_block:
                method,name=pending.pop(0);state['cells'][method+'/'+name]={'status':'running'}
                active[pool.submit(worker,method,name)]=(method,name)
            checkpoint()
            if not active:break
            done,_=wait(active,timeout=30,return_when=FIRST_COMPLETED)
            for f in done:
                method,name=active.pop(f);result=f.result();state['cells'][method+'/'+name]=result
                if '402' in (result.get('error') or ''):payment_block=True
    for method,name in pending:state['cells'][method+'/'+name]={'status':'not_started','reason':'provider still returns HTTP 402; no repeated probes'}
    state.update(status='payment_blocked' if payment_block else 'finished',finished_utc=utc());checkpoint()
    save(archive/'summary.json',state)


if __name__=='__main__':main()
