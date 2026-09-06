# Runtime-generated verification UT contract

The model returns one JSON object with exactly `intents` and `proofObligations`.
Each intent contains `label` and `expression`: a Scala expression returning `Gen.Expr` (hardware Bool, Sequence or Property).
Each proof obligation contains `label` and `reason`: metadata requiring a separate formal check.
Labels are unique snake_case identifiers. JSON strings escape embedded quotes and newlines.

The experiment manifest defines the imported RTL, ports, clock/reset wiring and sequence item type.
The framework generates one shared `VerilogWrapper` binding and a new UT for each intent in the run directory.
Only the model's expression is inserted into `Gen(expression, label)` in that UT's module body.
No benchmark-specific UT, stdlib module, historical scenario or DUT implementation is available to the active runner.

Use declared `io` ports, Bool operators, clocked sequences and properties; no semantic category wrappers.
A Scala block can declare local predicates and use `Gen.past(signal, width, cycles)` inside the Gen context.
History is reset-initialized and Gen automatically checks that all requested history is real at the goal's starting cycle.
ClockEvent, ClockScope, ResetScope and Gen.Scope are provided; all non-clock/reset inputs are free unless constrained.
Do not emit imports, objects, modules, backend calls, raw unguarded history windows or another Gen call in the response.
Prefer finite-witness goals. Arbitrary infinite-time properties are not guaranteed to be supported by the generation backend.
scalac checks the expression against the actual generated IO types. Compiled Scala is not a security sandbox.

`proofObligations` are pending metadata, not solver results. `Generated` supplies a witness only for the encoded intent.
An input-only constraint does not prove a coverage target was reached. Replay establishes coverage gain, and a dedicated
reachability property is needed to prove dead code. `Unknown` must not be reported as `Infeasible` or coverage closure.

Three compiled examples show response serialization, goal expressions, and the generic solver-to-codec interface.
All values and predicates in these examples come from caller parameters; they contain no design-specific answers.
The response supplies an expression for a generated UT, not copies of the examples' helper objects or symbolic parameters.

RAG is limited to reviewed framework sources and generic usage examples. Design RTL, protocol data, historical
responses and solver witnesses belong to explicit experiment inputs or audit archives, never to the retrieval corpus.
Whole-source examples must come from the separate compiled-example allowlist; API references are verbatim excerpts.
Source validation records provenance and does not replace semantic review of newly approved content.
