"""Release only rebuildable caches of a terminal, fully archived paired design."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import uuid

CACHE_DIRS={'csrc','simv.daidir','__pycache__'}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').digest()


def validate(work,archive,summary=Path('flow/paired/summary.json')):
    if summary.is_absolute() or '..' in summary.parts:
        raise ValueError('summary must be a relative path inside the experiment')
    if not (work/summary).is_file() or not (archive/summary).is_file():
        raise ValueError('both working and archived paired summaries required')
    state=json.loads((work/summary).read_text())
    terminal = state.get('status') in ('completed','failed') or (
        state.get('diagnostic_only') is True and state.get('status') in ('passed','rejected_as_expected'))
    if not terminal:
        raise ValueError('running experiment cannot be pruned')
    if (work/summary).read_bytes() != (archive/summary).read_bytes():
        raise ValueError('archive summary differs')
    caches=[]
    for directory,dirs,files in os.walk(work,followlinks=False):
        directory=Path(directory)
        for name in list(dirs):
            path=directory/name
            other=archive/path.relative_to(work)
            if path.is_symlink():
                if not other.is_symlink() or os.readlink(path)!=os.readlink(other):
                    raise ValueError('archived directory link differs: '+str(path))
                dirs.remove(name)
                continue
            if name in CACHE_DIRS:
                dirs.remove(name)
                if not path.is_symlink(): caches.append(path)
            elif not other.is_dir() or other.is_symlink():
                raise ValueError('material directory not fully archived: '+str(path))
        for name in files:
            path=directory/name
            if name=='simv' and not path.is_symlink():
                caches.append(path); continue
            other=archive/path.relative_to(work)
            if path.is_symlink():
                if not other.is_symlink() or os.readlink(path)!=os.readlink(other):
                    raise ValueError('archived link differs: '+str(path))
            elif not other.is_file() or digest(path)!=digest(other):
                raise ValueError('material artifact not fully archived: '+str(path))
    return caches


def relocate(work, archive, summary=Path('flow/paired/summary.json')):
    """Replace a verified terminal scratch tree with a link to its durable copy."""
    work, archive = Path(work).absolute(), Path(archive).resolve()
    if work.is_symlink():
        raise ValueError('working tree is already a symlink')
    work = work.resolve()
    if work == archive or work in archive.parents or archive in work.parents:
        raise ValueError('independent work and archive directories required')
    validate(work, archive, summary)
    retired = work.with_name(work.name + '.retired-' + uuid.uuid4().hex)
    work.rename(retired)
    try:
        work.symlink_to(archive, target_is_directory=True)
    except BaseException:
        retired.rename(work)
        raise
    # All non-cache bytes were verified above. Absolute historical paths now
    # resolve through the link; only this exact retired duplicate is removed.
    shutil.rmtree(retired)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--work',type=Path,required=True); p.add_argument('--archive',type=Path,required=True)
    p.add_argument('--summary-relative',type=Path,default=Path('flow/paired/summary.json'),
                   help='terminal paired summary within the work directory (also supports offline runs)')
    p.add_argument('--prune',action='store_true')
    p.add_argument('--relocate',action='store_true',help='replace verified terminal scratch tree with an archive symlink')
    args=p.parse_args()
    work=args.work.resolve(); archive=args.archive.resolve()
    if work==archive or work in archive.parents or archive in work.parents:
        raise ValueError('independent work and archive directories required')
    caches=validate(work,archive,args.summary_relative)
    total=sum(f.stat().st_size for path in caches for f in (path.rglob('*') if path.is_dir() else [path]) if f.is_file() and not f.is_symlink())
    if args.relocate:
        relocate(work, archive, args.summary_relative)
    elif args.prune:
        for path in caches:
            if not path.is_relative_to(work) or path.name not in CACHE_DIRS|{'simv'}:
                raise ValueError('invalid cache target')
            if path.is_dir(): shutil.rmtree(path)
            else: path.unlink()
    print(json.dumps(dict(pruned=args.prune or args.relocate,relocated=args.relocate,
                          cache_entries=len(caches),bytes=total,
                          material_artifacts='verified identical to archive and retained',work=str(work),archive=str(archive))))


if __name__=='__main__': main()
