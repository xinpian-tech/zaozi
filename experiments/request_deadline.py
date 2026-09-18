"""Total HTTP-request deadlines for the Linux, single-request model worker.

Socket inactivity timeouts alone permit an indefinitely trickling response.
Expire the whole request without retrying an invocation that may already be billed.
"""
from contextlib import contextmanager
import math
import signal
import threading

POLICY = 'whole-http-request-wall-clock-v1'


class RequestDeadlineExceeded(RuntimeError):
    """Not an OSError: the provider retry loop must not automatically resend it."""
    failure_kind = 'provider_request_deadline'
    model_repair_allowed = False


@contextmanager
def request_deadline(seconds):
    if isinstance(seconds, bool) or not isinstance(seconds, (int, float)) or not math.isfinite(seconds) or seconds <= 0:
        raise ValueError('request deadline must be positive and finite')
    if threading.current_thread() is not threading.main_thread() or not hasattr(signal, 'setitimer'):
        raise RuntimeError('total request deadlines require the Linux model-worker main thread')
    if any(signal.getitimer(signal.ITIMER_REAL)):
        raise RuntimeError('request deadline cannot replace an existing real-time timer')
    previous = signal.getsignal(signal.SIGALRM)

    def expired(signum, frame):
        raise RequestDeadlineExceeded(
            f'provider total request deadline exceeded ({seconds}s); '
            'usage may be unknown; no automatic resend; provider cancellation is not guaranteed')

    signal.signal(signal.SIGALRM, expired)
    try:
        signal.setitimer(signal.ITIMER_REAL, seconds)
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)
