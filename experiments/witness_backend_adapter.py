"""Experiment file/transport bindings for the RVProbe backend.

No acceptance, unknown-state encoding, temporal rewriting or retry policy here.
"""
from pathlib import Path
import backend_imports
from rvprobe.backend.runtime import ReplayTransport, WitnessBackend
from rvprobe.backend.failures import ReplayInfrastructureFailure
from cycle_replay import witness_frames
from haven_shared import render_witness_sequence
from witness_sampling import frozen_inputs, sample_goal, expand_goals
from encoded_candidate_search import candidates


def generate(result, replay_path, design, replay, simulator, directory, args, ordinal):
    if simulator is None:
        raise ReplayInfrastructureFailure('RVProbe sequence generation requires native replay; no unchecked fallback')
    source = Path(result['sources']).parent/'solve'
    goals = expand_goals(result, replay_path, directory/'sampling', args)

    def replenish(goal, out, budget):
        _, _, job, originals = frozen_inputs(source, replay_path)
        original = next(g for g in originals if g['label'] == goal['label'])
        return sample_goal(job, original, design, replay, out,
            budget, args.sampling_seed, args.sampling_time_limit, args.eda_shell)

    def known_state(goal, out, budget):
        return candidates(source, replay_path, goal['label'], out,
            args.encoded_witness_yosys, args.eda_shell, budget, args.sampling_seed,
            time_limit=getattr(args, 'encoded_witness_time_limit', '120s'))

    def measure(source, frames):
        _, result = simulator.measure_one([source], frames)
        return result['replay']

    backend = WitnessBackend(ReplayTransport(
        frames=lambda row, segment: witness_frames(design, replay, row, segment),
        render=lambda rows, name, offset: render_witness_sequence(design, rows, name, offset),
        measure=measure), replenish,
        known_state if getattr(args, 'encoded_witness_yosys', None) else None)
    return backend.generate(goals, directory/'native-witness-search',
                            f'rvp_{directory.name.replace("-", "_")}', args.sequences_per_intent, ordinal)
