"""Frozen-file/trace adapter for the model-free RVProbe candidate backend."""
import backend_imports
from rvprobe.backend.candidates import candidates as search
from witness_sampling import frozen_inputs, import_sample
from environment_contract import formal_assumptions


def candidates(source, replay, label, directory, yosys, eda_shell, count, seed, time_limit='120s'):
    design, config, job, goals = frozen_inputs(source, replay)
    goal = next(g for g in goals if g['label'] == label)
    return search(design, config, job, goal, formal_assumptions(design, config['environment']),
                  directory, yosys, eda_shell, count, seed,
                  lambda path, name: import_sample(path, name, design, event_mode=True), time_limit=time_limit)
