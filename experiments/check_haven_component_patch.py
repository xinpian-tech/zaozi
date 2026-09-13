"""Check exact HAVEN patch base/installed hashes without changing the checkout."""
import argparse
import hashlib
import json
from pathlib import Path


def check(root):
    record = json.loads((Path(__file__).parent / 'patches/haven-axi-transaction-contract.json').read_text())
    states = {}
    for name, hashes in record['files'].items():
        path = Path(root) / name
        actual = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
        states[name] = 'installed' if actual == hashes['after'] else 'base' if actual == hashes['before'] else 'different'
    status = next(iter(set(states.values()))) if len(set(states.values())) == 1 else 'mixed-or-different'
    return {'status': status, 'contract': record['contract'], 'files': states}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('haven_root', type=Path)
    args = parser.parse_args()
    result = check(args.haven_root)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['status'] in ('installed', 'base') else 1)
