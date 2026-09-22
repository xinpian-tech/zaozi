"""Archive closed simulation directories before releasing rebuildable caches.

Only explicitly configured experiment roots are touched. Coverage databases,
sources, witnesses, logs and accounting remain available at their original paths.
"""
import os
from pathlib import Path
import shutil

from prune_archived_caches import validate
from run_records import save, utc


def configured_roots():
    work = os.environ.get('RVPROBE_STORAGE_WORK_ROOT')
    archive = os.environ.get('RVPROBE_STORAGE_ARCHIVE_ROOT')
    if not work and not archive:
        return None
    if not work or not archive:
        raise ValueError('both experiment storage roots must be configured')
    work, archive = Path(work).resolve(), Path(archive).resolve()
    if work == archive or work in archive.parents or archive in work.parents:
        raise ValueError('independent experiment storage roots required')
    return work, archive


def require_space(directory, minimum=256 * 1024 * 1024):
    if configured_roots() is None:
        return
    ancestor = Path(directory)
    while not ancestor.exists():
        ancestor = ancestor.parent
    free = shutil.disk_usage(ancestor).free
    if free < minimum:
        raise OSError(f'experiment scratch space too low: {free} bytes available; need {minimum}')


def archive_tree(source, destination):
    """Merge a checkpoint into its archive without recreating identical links."""
    source, destination = Path(source), Path(destination)
    def ignored(directory, names):
        skip = set(names) & {'csrc', 'simv', 'simv.daidir', '__pycache__'}
        for name in set(names) - skip:
            item = Path(directory)/name
            other = destination/item.relative_to(source)
            # A closed child may already have been verified and relocated to
            # this exact archive location while its parent is still running.
            if item.is_symlink() and other.is_dir() and not other.is_symlink() and item.resolve() == other.resolve():
                skip.add(name)
                continue
            if other.is_symlink():
                if not item.is_symlink() or os.readlink(item) != os.readlink(other):
                    raise ValueError(f'archive link conflicts: {other}')
                skip.add(name)
        return skip
    shutil.copytree(source, destination, dirs_exist_ok=True, symlinks=True, ignore=ignored)


def archive_closed_simulation(directory):
    roots = configured_roots()
    if roots is None:
        return
    work, archive = roots
    directory = Path(directory).resolve()
    if directory == work or not directory.is_relative_to(work):
        raise ValueError('simulation is outside the configured experiment work root')
    if not directory.is_dir():
        return
    destination = archive / directory.relative_to(work)
    # This is an IO-lifecycle marker, never a simulation/paired success verdict.
    marker = Path('storage-checkpoint.json')
    save(directory / marker, {'status': 'completed', 'scope': 'closed-simulation-io-only',
                             'simulation_success_implied': False, 'closed_utc': utc()})
    archive_tree(directory, destination)
    caches = validate(directory, destination, marker)
    for path in caches:
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink()
