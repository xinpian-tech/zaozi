"""Bounded known-state candidate search; only native replay can accept its outputs."""
import hashlib
import json
from pathlib import Path

from .records import save, utc
from .encoding import solve, EncodingOptions, UnsupportedTemporalForm

def candidates(design, config, job, goal, environment_terms, directory, yosys, eda_shell,
               count, seed, import_trace, time_limit='120s'):
    label = goal['label']
    if config.get('environment', {}).get('boundary') != 'independent-dut-v1':
        raise ValueError('encoded candidates require the explicit independent DUT boundary')
    directory.mkdir(parents=True, exist_ok=False)
    record = dict(policy='encoded-native-checked-v1', source_job=job['fingerprint'],
                  status='running', solve_time_limit=time_limit,
                  original_goal_proven=False, attempts=[], remote_llm_requests=0,
                  diversity_policy='bounded-single-cell-on-duplicate-v1')
    script = Path(__file__).with_name('encoding.py')
    record['implementation_sha256'] = {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (
        Path(__file__), script, Path(__file__).with_name('initialization.py'),
        Path(__file__).with_name('past.py'))}
    # Establish the auxiliary model's own proved length first. A two-state
    # witness may rely on initially arbitrary state and be too short for a
    # known-state candidate. This is new solving, not padding the old trace.
    horizon = None
    prior_inputs = {}
    distinct_mode = False
    for index in range(count):
        item = dict(index=index, started_utc=utc(), status='running')
        record['attempts'].append(item)
        save(directory/'search.json', record)
        out = directory/f'candidate-{index}'
        options = EncodingOptions(out=out, project=directory/f'project-{index}',
            yosys=yosys, eda_shell=eda_shell, label=label, jg_time_limit=time_limit,
            noncontending_tristates=True,
            trace_cycles=horizon if index else None,
            trace_seed=seed+index if index else None,
            avoid_stimulus=tuple(Path(p) for p in prior_inputs.values()) if index and distinct_mode else ())
        try:
            summary = solve(design, config, job, goal, environment_terms, options)
            item['status'] = summary['status']
            item['solver_status'] = summary.get('solver_status')
            item['termination_reason'] = summary.get('termination_reason', summary['status'])
            item['elapsed_seconds'] = summary.get('elapsed_seconds')
            if summary['status'] == 'no_distinct_candidate':
                # Failed subset search is not unreachability of the original
                # goal. Try another deterministic cell within the SAME budget.
                continue
            if summary['status'] != 'covered':
                # Uniform seed changes are only soft preferences, not a cure
                # for an unsupported or inconsistent auxiliary model.
                record.update(status='stopped', stop_reason=summary['status'],
                              termination_reason=item['termination_reason'],
                              unreachability_proven=False)
                return
            sampled = import_trace(out/'witness.vcd', label)
            if horizon is None:
                horizon = sampled['cycles']
                record.update(original_cycles=goal['cycles'], auxiliary_proved_cycles=horizon)
            row = {k:goal[k] for k in ('generationLabel','utModule','utSourceSha256','fingerprint','engine')}
            row.update(sampled)
            row.update(origin='encoded-native-candidate-v1', original_goal_proven=False,
                       auxiliary_encoding=str(out/'identity.json'))
            item['input_fingerprint'] = row['inputFingerprint']
            duplicate = row['inputFingerprint'] in prior_inputs
            item['duplicate'] = duplicate
            if duplicate:
                distinct_mode = True
            else:
                prior_inputs[row['inputFingerprint']] = str(out/'witness.json')
            yield row
        except UnsupportedTemporalForm as error:
            # A missing optional concretization form must not discard other
            # native-valid goals or masquerade as an invalid model expression.
            # Keep this intent unresolved and return the already-valid subset.
            item.update(status='unsupported', kind='auxiliary_temporal_unsupported',
                        error=str(error), termination_reason='unsupported_temporal_form')
            record.update(status='stopped', stop_reason='unsupported_temporal_form',
                          termination_reason='unsupported_temporal_form',
                          unreachability_proven=False)
            return
        except GeneratorExit:
            record.update(status='stopped', stop_reason='consumer_closed')
            raise
        except Exception as error:
            item.update(status='failed', error=str(error))
            record.update(status='failed', stop_reason='backend_exception')
            raise
        finally:
            item['finished_utc'] = utc()
            save(directory/'search.json',record)
    record.update(status='finished', stop_reason='candidate_budget')
    save(directory/'search.json',record)
