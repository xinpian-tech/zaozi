"""Positive permission for model source repair; unknown failures stop closed."""


RESOURCE_REPAIR_POLICY = 'one-no-progress-resource-repair-v1'


def _resource_partial(shortfall):
    """Identify only a usable partial with exclusively resource-limited goals.

    No-witness, infeasibility, malformed reports and mixed failure classes must
    retain their normal repair budget. Unknown is not proof of unreachability.
    """
    if (not isinstance(shortfall, dict) or shortfall.get('phase') != 'solve'
            or shortfall.get('ok') is not False or shortfall.get('kind') != 'model_goal_shortfall'):
        return None
    result = shortfall.get('result', {})
    if not isinstance(result, dict) or result.get('status') != 'partial':
        return None
    goals, errors = result.get('goals'), shortfall.get('errors')
    if not isinstance(goals, list) or not goals or not isinstance(errors, list) or not errors:
        return None
    generated, unresolved, labels = set(), {}, set()
    for goal in goals:
        if not isinstance(goal, dict):
            return None
        label = goal.get('label')
        if not isinstance(label, str) or not label or label in labels:
            return None
        labels.add(label)
        if goal.get('status') == 'generated':
            generated.add(label)
        elif goal.get('status') == 'unknown':
            unresolved[label] = 'jg_goal_unknown'
        elif goal.get('status') == 'error' and goal.get('failureKind') == 'property_compile_timeout':
            unresolved[label] = 'jg_goal_compile_timeout'
        else:
            return None
    if not generated or not unresolved or len(errors) != len(unresolved):
        return None
    reported = {}
    for error in errors:
        if not isinstance(error, dict) or error.get('file') != 'model.ltl':
            return None
        label = error.get('goal')
        if not isinstance(label, str) or label in reported:
            return None
        reported[label] = error.get('code')
    return (generated, unresolved) if reported == unresolved else None


def resource_repair_stalled(previous, current):
    """Stop another local retry only after one resource repair made no progress.

    Both solves must retain the same usable goals and the same unresolved labels
    and resource failure classes. The best partial is still replayed and every
    unresolved goal remains available to subsequent coverage-round feedback.
    """
    before, after = _resource_partial(previous), _resource_partial(current)
    return before is not None and after == before


def model_repair_allowed(report):
    if report.get('ok') or report.get('model_repair_allowed') is False:
        return False
    if report.get('phase') in ('response-check', 'backend-check'):
        return bool(report.get('errors'))
    if report.get('phase') == 'typecheck':
        errors = report.get('errors')
        return bool(errors) and all(isinstance(error, dict) and error.get('file') == 'model.ltl'
                                    for error in errors)
    if report.get('phase') == 'elaboration-check' and report.get('kind') == 'model_argument_error':
        errors = report.get('errors')
        return bool(errors) and all(isinstance(error, dict) and error.get('file') == 'model.ltl'
                                    and error.get('code') in ('ltl_unsigned_range', 'ltl_signed_range')
                                    for error in errors)
    if report.get('phase') == 'solve' and report.get('kind') == 'model_goal_unsupported':
        errors=report.get('errors')
        return bool(errors) and all(isinstance(error,dict) and error.get('file')=='model.ltl'
            and error.get('code')=='jg_unsupported_liveness_cover' and isinstance(error.get('goal'),str)
            and bool(error['goal']) for error in errors)
    # Source/provenance input checks, toolchain, lower, wiring, initial state,
    # Other solver errors, filesystem and unknown harness phases are never LTL repair tasks.
    return False
