"""Inspect a batch's actual owner; optionally persist reconciled stopped state."""
import argparse
import json
from pathlib import Path
from batch_lifecycle import observe, process_identity
from run_records import save


def reconcile(work):
    work=Path(work).resolve()
    view=observe(work)
    if view['observation']['owner_live']:
        raise ValueError('cannot reconcile an active batch')
    if view['status']!='interrupted':
        return view
    # Preserve the old status verbatim before repairing only bookkeeping.
    original=json.loads((work/'progress.json').read_text())
    backup=work/'progress.before-reconcile.json'
    if not backup.exists():
        save(backup,original)
    if process_identity(original.get('pid')) is not None:
        raise ValueError('owner PID became live during reconciliation; inspect manually')
    roots=[work]
    if view.get('archive_root'):
        roots.append(Path(view['archive_root']))
    for root in roots:
        if root!=work and not (root/'progress.before-reconcile.json').exists():
            save(root/'progress.before-reconcile.json',original)
        save(root/'progress.json',view)
        save(root/'summary.json',view)
    return view


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--batch',type=Path,required=True)
    parser.add_argument('--reconcile',action='store_true')
    args=parser.parse_args()
    result=reconcile(args.batch) if args.reconcile else observe(args.batch)
    print(json.dumps(dict(status=result['status'],observation=result['observation'],
        designs={name:{k:item[k] for k in ('status','error','summary','reason') if k in item}
                 for name,item in result['designs'].items()}),indent=2))


if __name__=='__main__': main()
