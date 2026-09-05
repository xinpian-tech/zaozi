# Fixed-runner response interface

The model returns two Scala declarations: `cases: Seq[(String, Int, Long, Long)]` and
`proofObligations: Seq[(String, String)]`. Each case holds a label, an integer code, and two
Long values. Each obligation holds a label and a reason string. The runner supplies imports,
the experiment object, solver calls, and output serialization; the response must not replace them.

Use unique labels, quoted strings, comma-separated tuples, and balanced parentheses for both
each tuple and its enclosing `Seq`. Scala Long literals require the `L` suffix. An empty list
is `Seq()`. Return both declarations without a markdown fence or extra statements.

## Worked framework examples

The corpus includes three compiled examples, each labelled with a task, supplied inputs,
and a solution. All predicates and candidate values are caller parameters, not design answers:

- [Typed tuple construction](../src/rag/FrameworkDataExample.scala): non-empty `cases` and
  `proofObligations` declarations built from supplied values and pending metadata.
- [Semantic composition](../src/rag/FrameworkSemanticsExample.scala): combine `Sem.value`,
  `Sem.relation`, `Sem.state`, and `Sem.temporal`, with the union type and clock context.
- [Generation and export](../src/rag/FrameworkPipelineExample.scala): `UTGenerator` ABI,
  JasperGold lowering/generation, all three `GenerateOutcome` branches, and stimulus/UVM export.

These are complete, parameterized Scala helpers for teaching framework use. Their objects,
methods, and solver calls are not an alternative output contract. In a fragment-only task,
return just the two declarations with task-derived literals; do not copy symbolic example
parameter names into the response or change the fixed runner.

The manifest references these files with `whole_source: true`; the loader reads the same
source files compiled by `experiments.compile`. Full-file retrieval is restricted to this
separate example allowlist, and content overrides are rejected. Unit-test fixtures are not
retrieval sources. Source hashes still record exactly which version was injected.

The empty shape below documents types only; it is not a test input or a proof result:

```scala
val cases: Seq[(String, Int, Long, Long)] = Seq()
val proofObligations: Seq[(String, String)] = Seq()
```

`proofObligations` is metadata for a separate proof step. Merely returning an entry does not
run that proof or establish its conclusion. A solver witness satisfies the encoded intent;
coverage of a target not encoded in that intent must be measured separately during replay.

## Corpus boundary

RAG is limited to framework API signatures, types, interface documentation, and generic usage examples. Current design
source and coverage reports belong to task evidence, outside this corpus. Historical test inputs,
model answers, solver witnesses, coverage-closure recipes, and design-specific proof conclusions
must not be indexed. The loader accepts only reviewed source paths and verbatim source excerpts;
this is a provenance check, not a substitute for reviewing additions to framework documentation.
