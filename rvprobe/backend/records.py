"""Backend JSON provenance and phase timing, independent of experiment accounting."""
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import time
import uuid

def utc():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")
    temporary.replace(path)


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

class Records:
    def __init__(self, directory):
        self.path = Path(directory) / "events.jsonl"
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def append(self, record):
        with self.path.open("a") as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
            stream.flush()
            os.fsync(stream.fileno())

    @contextmanager
    def phase(self, phase, **fields):
        event = {"id": uuid.uuid4().hex, "phase": phase, "started_utc": utc(), "status": "running", **fields}
        began = time.monotonic()
        self.append(event)
        try:
            yield event
            if event["status"] == "running":
                event["status"] = "ok"
        except BaseException as error:
            event.update(status="failed", error_type=type(error).__name__)
            if getattr(error, "code", None) is not None:
                event["error_code"] = error.code
            raise
        finally:
            event.update(finished_utc=utc(), seconds=time.monotonic() - began)
            self.append(event)
