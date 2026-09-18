"""Terminal provider outcomes, separate from model-source repair diagnostics."""


class ProviderFailure(RuntimeError):
    def __init__(self, message, kind):
        super().__init__(message)
        self.failure_kind = kind
        self.model_repair_allowed = False


def incomplete_response(info, message):
    return ProviderFailure(message, info.get('response_failure_kind') or 'provider_incomplete_response')
