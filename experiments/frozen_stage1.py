"""Read-only identity checks for shared setup, independent of the LTL author."""
import json
from pathlib import Path

from cycle_replay import digest
from haven_shared import checkout_hashes


def stage1_identity(stage, shared_environment=False):
    stage=Path(stage).resolve()
    ir=stage/'ir'
    for name in ('phase0_config.json','phase2b_blueprint.json',
                 'phase4b_dsl_sequences.json','phase5_compile_check_result.json'):
        if not (ir/name).is_file():
            raise ValueError('fixed Stage-1 is incomplete: '+str(ir/name))
    if (stage/'manual-diagnostic.json').exists() and not shared_environment:
        raise ValueError('diagnostic Stage-1 must not be relabeled as a fixed benchmark baseline')
    if not json.loads((ir/'phase5_compile_check_result.json').read_text()).get('compile_passed'):
        raise ValueError('fixed Stage-1 did not pass compilation')
    dsl=json.loads((ir/'phase4b_dsl_sequences.json').read_text())
    if not dsl.get('sequences') or not list((stage/'final').glob('sequence_*.sv')):
        raise ValueError('fixed Stage-1 has no baseline sequences')
    task=json.loads((ir/'phase0_config.json').read_text())
    files=[p for directory in (ir,stage/'final') for p in directory.rglob('*') if p.is_file()]
    if (stage/'manual-diagnostic.json').exists():
        files.append(stage/'manual-diagnostic.json')
    files += [(Path(task['root'])/name).resolve() for name in task['rtl_files']]
    if task.get('spec'): files.append((Path(task['root'])/task['spec']).resolve())
    return dict(stage1=str(stage),shared_environment=shared_environment,
                sha256={str(p):digest(p) for p in sorted(set(files))})


def load_fixed_setup(record):
    record=Path(record).resolve()
    setup=json.loads(record.read_text())
    shared = setup.get('role') == 'shared-stage1-environment'
    if setup.get('status')!='stage1_ready' or (setup.get('diagnostic_only') and not shared):
        raise ValueError('experiment requires an already passing fixed Stage-1 record')
    # Stage-1 provenance may name another author/tool. It is not regenerated
    # merely to match the selected sequence/LTL model.
    identity=stage1_identity(setup['stage1'],shared)
    identity.update(record=str(record),record_sha256=digest(record),
                    haven_root=str(Path(setup['haven_snapshot']).resolve()),
                    haven_sha256=checkout_hashes(Path(setup['haven_snapshot'])))
    return setup,identity


def verify_stage1(identity):
    if stage1_identity(identity['stage1'],identity.get('shared_environment',False)) != {
            k:identity[k] for k in ('stage1','sha256','shared_environment')}:
        raise ValueError('fixed Stage-1 artifacts or RTL changed; experiment cannot regenerate them')


def admit_shared_environment(bundle, identity):
    """Adopt a setup as shared infrastructure without changing its authorship."""
    if not identity.get('shared_environment'):
        raise ValueError('explicit shared Stage-1 environment admission required')
    verify_stage1(identity)
    config = str(Path(identity['stage1'])/'ir/phase0_config.json')
    if bundle['sources'].get(config) != identity['sha256'].get(config):
        raise ValueError('shared environment admission does not match the bundle')
    for path, sha in bundle['sources'].items():
        if identity['sha256'].get(path) != sha:
            raise ValueError('bundle source is outside the frozen shared environment: '+path)
    return dict(role='shared-stage1-environment',stage1=identity['stage1'],
                provenance=bundle.get('manual_baseline_provenance'),
                stage1_model_calls=0,stage2_authorship='provider-model')


def verify_fixed_setup(identity):
    verify_stage1(identity)
    if (digest(Path(identity['record'])) != identity['record_sha256'] or
            checkout_hashes(Path(identity['haven_root'])) != identity['haven_sha256']):
        raise ValueError('fixed Stage-1 record or shared implementation changed')
