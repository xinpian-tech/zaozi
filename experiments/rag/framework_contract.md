# Runtime-generated verification UT contract

The model returns one JSON object with exactly `intents` and `proofObligations`.
Each intent contains `label` and `expression`: a Scala expression returning a typed `Sem.Intent`.
Each proof obligation contains `label` and `reason`: metadata requiring a separate formal check.
Labels are unique snake_case identifiers. JSON strings escape embedded quotes and newlines.

The experiment manifest defines the imported RTL, ports, clock/reset wiring and sequence item type.
The framework generates one shared `VerilogWrapper` binding and a new UT for each intent in the run directory.
Only the model's expression is inserted into `Generate(expression, label)` in that UT's module body.
No benchmark-specific UT, stdlib module, historical scenario or DUT implementation is available to the active runner.

The expression can use the declared `io` ports and compose `Sem.value`, `Sem.state`, `Sem.relation` and `Sem.temporal`.
A Scala block can declare local predicates or a `Txn.window` and return a composed intent.
ClockEvent, ClockScope and ResetScope are provided by the generated UT; all non-clock/reset inputs are free unless constrained.
Do not emit imports, objects, modules, backend calls or another Generate call in the response.
scalac checks the expression against the actual generated IO types. Compiled Scala is not a security sandbox.

`proofObligations` are pending metadata, not solver results. `Generated` supplies a witness only for the encoded intent.
An input-only constraint does not prove a coverage target was reached. Replay establishes coverage gain, and a dedicated
reachability property is needed to prove dead code. `Unknown` must not be reported as `Infeasible` or coverage closure.

Three compiled examples show response serialization, semantic composition, and the generic solver-to-codec interface.
All values and predicates in these examples come from caller parameters; they contain no design-specific answers.
The response supplies an expression for a generated UT, not copies of the examples' helper objects or symbolic parameters.

RAG is limited to reviewed framework sources and generic usage examples. Design RTL, protocol data, historical
responses and solver witnesses belong to explicit experiment inputs or audit archives, never to the retrieval corpus.
Whole-source examples must come from the separate compiled-example allowlist; API references are verbatim excerpts.
Source validation records provenance and does not replace semantic review of newly approved content.
