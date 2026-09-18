"""Read-only liveness reconciliation; a stale JSON 'running' is not evidence."""
from copy import deepcopy
import json
import os
from pathlib import Path
from run_records import utc


def process_identity(pid):
    try:
        fields=(Path(f'/proc/{int(pid)}/stat').read_text().rsplit(')',1)[1].split())
        if fields[0] in ('Z','X'):
            return None
        return dict(pid=int(pid),start_ticks=fields[19],
                    boot_id=Path('/proc/sys/kernel/random/boot_id').read_text().strip())
    except (OSError,ValueError,IndexError,TypeError):
        return None


def current_owner():
    identity=process_identity(os.getpid())
    if identity is None:
        raise RuntimeError('cannot identify the batch owner process')
    return identity


def observe(work):
    work=Path(work)
    record=json.loads((work/'progress.json').read_text())
    view=deepcopy(record)
    owner=record.get('owner')
    actual=process_identity(record.get('pid'))
    live=actual is not None and (owner is None or actual==owner)
    view['observation']=dict(observed_utc=utc(),owner_live=live,
                             owner_identity_verified=bool(owner and live),
                             recorded_status=record.get('status'))
    if record.get('status')=='running' and not live:
        view['status']='interrupted'
        view['interruption_reason']='batch owner is absent; terminal child records recovered where available'
        for name,item in view.get('designs',{}).items():
            if not name or Path(name).name != name or name in ('.','..'):
                raise ValueError('invalid batch design directory')
            summary=work/name/'flow/paired/summary.json'
            if summary.is_file():
                child=json.loads(summary.read_text())
                if child.get('status') in ('completed','failed'):
                    item.update(status=child['status'],summary=str(summary),
                                child_finished_utc=child.get('finished_utc'))
                    continue
            if item.get('status')=='queued':
                item.update(status='not_started',reason='batch owner exited before dispatch')
            elif item.get('status','').startswith(('running','validating')):
                item['status']='interrupted'
    return view
