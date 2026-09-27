"""Select and run the simulator backend used by a unit test."""


def run(name, source_dir, top, frontend, build_dir, circuit_dir, timeout):
    if name == "verilator":
        from backend.verilator.run import run as run_verilator

        return run_verilator(source_dir, top, frontend, build_dir, circuit_dir, timeout)
    if name == "vcs":
        from backend.vcs.run import run as run_vcs

        return run_vcs(source_dir, top, frontend, build_dir, circuit_dir, timeout)
    raise ValueError(f"unsupported backend: {name}")
