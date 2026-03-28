# IDEA.md

Paper research ideas and experiment design notes for ICCAD 2026 resubmission.

## Core Argument: Abstraction Level Mismatch

Existing directed test generation methods (hand-written assembly, SystemVerilog constraints) operate at the **single-instruction level**, but verification goals (hazard coverage, data dependencies) are inherently **sequence-level**.

- **Hand-written assembly**: The engineer mentally solves the constraint satisfaction problem (bin coverage + hazard patterns simultaneously). Error-prone, not scalable.
- **SV constraints**: Can express per-instruction constraints natively, but sequence-level constraints (e.g., "there exists a RAW pair among 35 instructions") require manual workarounds — auxiliary index variables, explicit equality constraints, hazard-type exclusions. Covergroups only observe coverage, they don't drive generation.
- **RVProbe eDSL**: Sequence-level constraints are natively supported — `coverRAW()` directly expresses cross-instruction dependencies, and the solver guarantees satisfaction.

**Key insight**: The problem is not "tool convenience" but a structural impedance mismatch between the abstraction level of existing methods and the abstraction level of verification goals. RVProbe eliminates this mismatch by making sequence-level constraints a first-class construct.

## RQ1: Sequence-Level Constraint Expressiveness

**Question**: How do existing directed testing methods handle sequence-level constraints, and what are the consequences of the abstraction mismatch?

**Experiment**: Implement the same coverage goal (register bin coverage + hazard type coverage for RV32I) using three methods:
1. RVProbe eDSL
2. Hand-written RISC-V assembly
3. SystemVerilog constrained-random

**Metrics**:
- Lines of code (constraint specification vs workaround boilerplate)
- Coverage guarantee (static/compile-time vs runtime vs manual)
- Ability to express sequence-level properties natively vs via encoding

**Representative instruction formats**: R-type (add), I-type ALU (addi), Shift-imm (slli), U-type (lui), B-type (beq), Load (lw), Store (sw)

**Comparison files**: `rvprobe/src/cases/output/comparison/`

## RQ2: White-Box Constraint Composition (T1 Bug)

Promote the T1 pipeline bug discovery from a case study to a formal research question. Show that composing architectural constraints (RAW hazard) with microarchitectural constraints (Reverse signal from OM extraction) can systematically expose implementation-dependent defects.

## Paper Reframing Notes

- Cut engineering details (5-layer architecture, FFI performance) — these are implementation, not contribution
- Align contributions with RQs
- Add DirectFuzz, RFUZZ, μSpec to related work with clear positioning
- Core narrative: not "better tool" but "right abstraction level for the problem"
