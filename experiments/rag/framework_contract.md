# LTL-only contract (runtime-ltl-v2)

The model returns raw Scala LTL: local predicates/pure helpers and
Gen(expression, "unique_snake_case_label"). No JSON, imports, module declaration,
architecture, wiring, clock/reset declaration or proof classification.
Gen accepts Referable[Bool], Sequence or Property. Use native past with explicit
history when required. No Assume, restrict, extra Assert/Cover or DUT internals.

The framework code-generates local port aliases from the IO manifest, preserving signal types.
Use the identifiers in the IO table directly, without io.; collisions with Scala/API
names are explicitly mapped. Bool/Sequence concatenation accepts ### and fixed/bounded
## with contextual Bool lifting, not a global implicit conversion.
The framework supplies the clock/reset context, extracts literal labels,
and deterministically builds one fixed UT from the fragment. It saves model.ltl
verbatim and independently hashes the generated ModelUT.scala. Each goal is solved
separately; other goals are neither conjoined nor assumed. Witnesses are checked
by raw native replay before coverage credit.

STOP alone means no new intent, not a proof of unreachability or coverage closure.
Old complete-UT JSON is no longer a model input format. Archived results are not
silently migrated or re-labelled as LTL-only measurements.

This overview is not indexed by RAG. The active index contains only Gen expression
types and native LTL API excerpts. The frozen rvprobe-skill.md supplies the core
semantics, usage and symbolic examples. See ../PROMPT.md for the complete current
execution/evidence contract.
