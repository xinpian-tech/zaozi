"""Fail closed at both experiment model boundaries during local diagnostics.

This is not an OS network sandbox: EDA license/remote-shell access is unchanged.
No credentials are needed or loaded by this guard.
"""
from contextlib import contextmanager, ExitStack
from unittest.mock import patch


class ModelCallsForbidden(RuntimeError):
    pass


def forbidden_request(*args, **kwargs):
    raise ModelCallsForbidden('offline validation forbids LLM calls, including automatic repair')


@contextmanager
def no_model_calls():
    import sequence_experiment
    from haven.utils.llm_client import LLMClient
    with ExitStack() as guard:
        guard.enter_context(patch.object(sequence_experiment, 'send_completion', forbidden_request))
        for method in ('call', 'call_structured'):
            guard.enter_context(patch.object(LLMClient, method, forbidden_request))
        yield
