# Single complete verification UT contract (runtime-ut-v5)

The model returns one JSON object with exactly `ut` and `proofObligations`.
The `ut` object contains `module`, `generationLabels` and `source`: ONE complete Scala verification UT source.
`generationLabels` lists all Gen labels exactly once (1..64 unique snake_case labels).
Each proof obligation contains `label` and `reason`: metadata requiring a separate formal check.
Labels are unique snake_case identifiers. JSON strings escape quotes and newlines. Multi-UT responses are rejected.

The experiment manifest defines the imported RTL, ports, clock/reset wiring and sequence item type.
The framework generates one shared `VerilogWrapper` binding and a fixed runner, NOT a UT body.
The model supplies imports and exactly one @generator object extending Generator with UT, implements architecture,
instantiates ImportedDut, faithfully wires every IO and clock/reset, declares clock/reset scopes and calls Gen for each goal.
Source is compiled verbatim. The single UT is lowered once; each goal is solved separately against that same model.
Other Gen assertions are removed from a selected goal's solver task, not conjoined or assumed.
Expression-only responses are rejected; no source fragments are wrapped automatically.
No benchmark-specific UT, stdlib module, historical scenario or DUT implementation is available to the active runner.

If no useful generation target remains, return exactly `stop` and `proofObligations` instead:
`{"stop":{"reason":"explain the lack of a new goal"},"proofObligations":[]}`.
No UT is generated in that case. Do not invent filler goals. A stop does not prove coverage closure.

Use declared IO, Bool operators, clocked sequences and properties; no semantic category wrappers.
Use native past(predicate, cycles) for Bool history, e.g. past(io.payload === 5.B(8), 2). It does not return historical Bits or add reset/history-valid guards.
The UT declares ClockEvent. Explicitly describe sufficient elapsed cycles within the goal when real history is required.
Do not generate Assume or other global restrictions, including reset assumptions.
Clock/reset initialization is fixed by the runner. All scenario-specific input and timing conditions belong inside Gen.
Do not tie DUT inputs to constants or change wiring to restrict the environment indirectly.
The runner rejects assumptions/restrictions in the emitted UT and requires all assertions to match the listed Gen labels.
All non-clock/reset inputs remain free outside the selected goal. Prefer finite-witness goals.
Do not replace the binding or runner, call backend tools, read files or import benchmark modules.
Compilation and elaboration run in a fail-closed Linux bubblewrap sandbox with no network or provider credentials.
Only toolchain/source inputs are read-only mounted; writes are limited to private work and temporary directories.
The trusted runner checks a single original DUT, exact ports, direct/alias wiring and reset polarity in emitted SV.
It then invokes the solver in a separate JVM that never loads model classes. Unsupported wiring is rejected.
Each goal has its own outcome/checkpoint; a failed or unknown goal does not discard successful witnesses.
Replay reports per-goal witness coverage and extra fixed-drain coverage separately. Input intent is not coverage proof.

`proofObligations` are pending metadata, not solver results. `Generated` supplies a witness only for the encoded intent.
An input-only constraint does not prove a coverage target was reached. Replay establishes coverage gain, and a dedicated
reachability property is needed to prove dead code. `Unknown` must not be reported as `Infeasible` or coverage closure.

Compiled examples show response serialization, complete UT wiring, goal expressions and solver-to-codec interfaces.
They contain generic framework patterns, not design-specific scenarios or coverage conclusions.
RAG is limited to reviewed framework sources and generic usage examples. Design RTL, protocol data, historical
responses and solver witnesses belong to explicit experiment inputs or audit archives, never to the retrieval corpus.
Whole-source examples must come from the separate compiled-example allowlist; API references are verbatim excerpts.
Source validation records provenance and does not replace semantic review of newly approved content.
