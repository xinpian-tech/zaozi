"""Positive permission for model source repair; unknown failures stop closed."""


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
