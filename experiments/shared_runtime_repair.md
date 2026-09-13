# Shared baseline runtime repair

Use only this task's original RTL, specification, actual driver and BFM APIs, and saved failure logs.
The same repaired baseline and infrastructure will be frozen for both experimental arms.

Each baseline sequence runs independently in a fresh simulation process with the declared reset.
Re-establish any state required by that sequence. Preserve sequence names, intents and meaningful
checks; do not delete polls, substitute tautologies, mask DUT errors, suppress reports, fabricate
observations, or modify RTL. Correct mistaken register/protocol interpretations using original RTL
when derived planning data contradicts it. Explain such corrections in sequence descriptions.

A driver owns only its declared inputs. Broadcast transactions do not mean every peripheral
protocol should start a transfer on every register read/write. Preserve the dispatch API and
implement each transaction's actual intended behavior. If the frozen API cannot express that
behavior, report the limitation instead of inventing a passing implementation.

This file contains no benchmark stimulus, register addresses, operands or coverage answers.
