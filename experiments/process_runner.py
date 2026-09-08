"""Bounded subprocesses: kill the complete process group on timeout/interruption."""
import os
import signal
import subprocess
from pathlib import Path


def descendants(pid):
    """Include nested runners that started their own sessions; never target unrelated PIDs."""
    children = []
    for entry in Path("/proc").iterdir():
        if entry.name.isdigit():
            try:
                fields = (entry / "stat").read_text().rsplit(")", 1)[1].split()
                if int(fields[1]) == pid:
                    children.append(int(entry.name))
            except (OSError, ValueError, IndexError):
                pass
    return [child for parent in children for child in [*descendants(parent), parent]]


def run(command, *, timeout=600, **kwargs):
    check = kwargs.pop("check", False)
    with subprocess.Popen(command, start_new_session=True, **kwargs) as process:
        try:
            stdout, stderr = process.communicate(timeout=timeout)
        except BaseException:
            for child in descendants(process.pid):
                try:
                    os.kill(child, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
            raise
        result = subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
        if check:
            result.check_returncode()
        return result
