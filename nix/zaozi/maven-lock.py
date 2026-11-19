"""Write the MTF Maven repository lock from a clean multi-repository Coursier run."""
import base64
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import unquote, urlsplit

REPOSITORIES = {
    "central": "https://repo.maven.apache.org/maven2",
    "scala-native": "https://central.sonatype.com/repository/maven-snapshots",
}


def record_repository(cache, url, run_id):
    uri = urlsplit(url)
    root = cache / uri.scheme / uri.netloc / uri.path.lstrip("/")
    artifacts = {}
    for pom in sorted(root.rglob("*.pom")):
        path = Path(unquote(str(pom.parent.relative_to(root))))
        files = sorted(p for p in pom.parent.iterdir() if not p.name.startswith(".")
                       and p.is_file() and p.name.split(".")[-1] in {"jar", "pom", "sha1", "md5"})
        hashes = {}
        # mkMavenRepository hashes the Maven directory tree inside each output.
        with tempfile.TemporaryDirectory() as tmp:
            artifact = Path(tmp) / path
            artifact.mkdir(parents=True)
            for source in files:
                digest = hashlib.sha256(source.read_bytes()).digest()
                filename = unquote(source.name)
                hashes[filename] = "sha256-" + base64.b64encode(digest).decode()
                shutil.copyfile(source, artifact / filename)
            nar = subprocess.check_output(["nix", "hash", "path", tmp], text=True).strip()
        artifacts[str(path)] = {"narHash": nar, "runs": [run_id], "files": hashes}
    return artifacts


def main(cache, output):
    runs, artifacts = {}, {}
    for name, repo in REPOSITORIES.items():
        command = ["./bump-mill-lock.sh"]
        run_id = hashlib.sha256((repo + "\n" + " ".join(command)).encode()).hexdigest()[:12]
        runs[run_id] = {"repository": name, "command": command}
        resolved = record_repository(cache, repo, run_id)
        if not resolved:
            raise ValueError(f"No dependencies resolved from {repo}")
        duplicates = artifacts.keys() & resolved.keys()
        if duplicates:
            raise ValueError(f"Maven coordinates resolved from two repositories: {duplicates}")
        artifacts.update(resolved)
    lock = {"version": 3, "kind": "mtf-maven-lock", "repositories": REPOSITORIES,
            "runs": runs, "artifacts": dict(sorted(artifacts.items()))}
    output.write_text(json.dumps(lock, indent=2) + "\n")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
