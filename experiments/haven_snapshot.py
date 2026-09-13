"""Freeze HAVEN implementation files without copying credentials or RTL datasets."""
from pathlib import Path
import shutil
from haven_shared import checkout_hashes
from run_records import save, utc


def snapshot(source, destination):
    source, destination = Path(source).resolve(), Path(destination).resolve()
    before = checkout_hashes(source)
    destination.mkdir(parents=True, exist_ok=False)
    shutil.copytree(source/'src', destination/'src', ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
    if checkout_hashes(destination) != before or checkout_hashes(source) != before:
        raise ValueError('HAVEN implementation changed while snapshotting; snapshot not usable')
    save(destination/'snapshot.json',{'source':str(source),'created_utc':utc(),'sha256':before,
                                    'policy':'implementation only; excludes provider configuration and HDL dataset'})
    return destination
