"""Mechanically refresh the portable patch against a verified, pristine base.

This does not apply changes to HAVEN, stage git files, or call models.
Additional paths require explicit --include and an original file in --base.
"""
import argparse
import difflib
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


def sha(data):
    return hashlib.sha256(data).hexdigest() if data is not None else None


def refresh(haven, base, manifest, patch, extra=()):
    record = json.loads(manifest.read_text())
    files = dict(record['files'])
    for name in extra:
        if name in files:
            raise ValueError('additional path is already recorded: '+name)
        original = base/name
        if not original.is_file():
            raise ValueError('additional path requires a saved original: '+name)
        files[name] = {'before':sha(original.read_bytes())}
    chunks = []
    for name, hashes in files.items():
        if Path(name).is_absolute() or '..' in Path(name).parts:
            raise ValueError('patch paths must be relative to HAVEN')
        original = (base/name).read_bytes() if (base/name).is_file() else None
        if sha(original) != hashes['before']:
            raise ValueError('original base hash differs: '+name)
        current = (haven/name).read_bytes()
        chunks.append('diff --git a/'+name+' b/'+name+'\n')
        if original is None:
            chunks.append('new file mode 100644\n')
        delta=difflib.unified_diff(
            (original or b'').decode().splitlines(keepends=True),
            current.decode().splitlines(keepends=True),
            fromfile='a/'+name if original is not None else '/dev/null', tofile='b/'+name)
        for line in delta:
            chunks.append(line if line.endswith('\n') else line+'\n\\ No newline at end of file\n')
        hashes['after'] = sha(current)
    content = ''.join(chunks)
    with tempfile.TemporaryDirectory(prefix='haven-patch-verify-') as directory:
        check = Path(directory)/'contract.patch'
        check.write_text(content)
        for root, flags in ((base,[]),(haven,['--reverse'])):
            subprocess.run(['git','apply','--check',*flags,str(check)],cwd=root,check=True)
    record['files'] = files
    # These two files are generated mechanical artifacts, never source rewrites.
    patch.write_text(content)
    manifest.write_text(json.dumps(record,indent=2)+'\n')
    return {'files':len(files),'forward_and_reverse_check':True}


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ('haven-root','base'):
        parser.add_argument('--'+key,type=Path,required=True)
    parser.add_argument('--include',nargs='*',default=[])
    args=parser.parse_args()
    root=Path(__file__).parent/'patches'
    print(json.dumps(refresh(args.haven_root.resolve(),args.base.resolve(),
        root/'haven-axi-transaction-contract.json',root/'haven-axi-transaction-contract.patch',args.include)))
