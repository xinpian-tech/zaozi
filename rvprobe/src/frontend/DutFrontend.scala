// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.frontend

/** ============================================================================
  * DUT frontend contract — the HDL-agnostic seam of RVProbe.
  *
  * RVProbe's core is language-independent: two-stage SMT solving, the
  * sequence-level cover API (coverRAW/WAR/WAW), fresh-register allocation, and
  * lowering to the shared CIRCT/MLIR SMT dialect. What is *specific* to a design
  * is confined to a [[DutFrontend]], which contributes:
  *
  *   1. [[DutFrontend.alphabet]] — the legal atomic stimuli and their field-level
  *      legality. For a RISC-V core this is the instruction set (opcodes +
  *      operand fields, generated from riscv-opcodes). For a Decoupled Chisel
  *      module it is the transaction kinds read off the typed IO. For raw Verilog
  *      it is a user-supplied interface description.
  *
  *   2. [[DutFrontend.whitebox]] — design-internal microarchitectural signals
  *      lifted as first-class constraint variables (Chisel/Zaozi Object Model;
  *      Verilog annotations). Empty ⇒ the DUT is treated black-box.
  *
  *   3. [[DutFrontend.solve]] / [[DutFrontend.backend]] — solve the DUT's recipe
  *      into a frontend-specific [[SolvedArtifact]], then render that artifact
  *      into runnable stimulus (RISC-V: GAS assembly; a Decoupled module:
  *      ChiselSim poke/peek/step). The lowering of DUT + constraints into CIRCT
  *      is shared across every frontend.
  *
  * The artifact is frontend-specific on purpose: wiring the RISC-V leg showed
  * that rendering needs more than the [[SolvedSequence]] field values (it needs
  * the recipe's statement layout). Each frontend therefore defines its own
  * `Artifact <: SolvedArtifact`, carrying the [[SolvedSequence]] plus whatever
  * its backend needs — so `render` never has to re-solve.
  *
  * Planned legs, cleanest first (see wishing/9-rvprobe-multilang-frontend):
  *   - Zaozi   — same IR, structured metadata: the reference for new legs.
  *   - Chisel  — Object Model extraction generalized beyond the T1 case.
  *   - Verilog — hardest/most valuable (no Object Model): alphabet from a
  *               supplied interface, whitebox from annotations/architectural
  *               state only. Lets a Verilog core (e.g. cv32e40x) be targeted
  *               without rewriting it in Chisel.
  * ============================================================================
  */
trait DutFrontend:
  /** The frontend-specific solved artifact its backend renders. */
  type Artifact <: SolvedArtifact

  /** Human-readable frontend/DUT name (used in generated output and logs). */
  def name: String

  /** The stimulus alphabet this DUT accepts. */
  def alphabet: StimulusAlphabet

  /** White-box microarchitectural predicates. Empty by default (black-box). */
  def whitebox: Seq[WhiteboxPredicate] = Seq.empty

  /** Solve the DUT's recipe/spec into its artifact (the SMT core's output plus
    * any design-specific structure the backend needs). */
  def solve(): Artifact

  /** Backend that renders an already-solved artifact — no re-solve. */
  def backend: StimulusBackend[Artifact]

  /** End-to-end: solve once, then render. */
  final def generate(): String = backend.render(solve())

/** The frontend-agnostic output of the SMT core: a solved stimulus sequence.
  *
  * @param selections sequence index -> chosen alphabet element id
  * @param fields     solved field name (e.g. "rs1_3", "imm12_8") -> value
  */
final case class SolvedSequence(
  selections: Map[Int, Int],
  fields:     Map[String, BigInt]
)

/** A frontend's fully-solved artifact: the frontend-agnostic [[SolvedSequence]]
  * plus whatever design-specific structure its backend needs to render (for the
  * RISC-V leg, the recipe statement layout). Backends render from this, so no
  * stage is re-run at render time. */
trait SolvedArtifact:
  def sequence: SolvedSequence

/** One atomic stimulus kind in a DUT's alphabet (an instruction opcode, a
  * transaction kind, …). Its legality is expressed as constraints over the
  * shared SMT layer by the owning frontend. */
trait StimulusKind:
  /** Stable id used in [[SolvedSequence.selections]]. */
  def id: Int

  /** Human-readable mnemonic (instruction name, transaction kind, …). */
  def mnemonic: String

/** The set of atomic stimuli a DUT accepts. */
trait StimulusAlphabet:
  def kinds: Seq[StimulusKind]

/** A design-internal microarchitectural signal exposed as a first-class
  * constraint dimension (from a Chisel/Zaozi Object Model or a Verilog
  * annotation). `category` groups related signals (e.g. an execution-unit
  * class), enabling the architecture-x-microarchitecture constraint matrix. */
trait WhiteboxPredicate:
  def signal:   String
  def category: String

/** Renders a frontend's solved artifact into runnable stimulus for the DUT's
  * world. Parameterized by the frontend's artifact type so no re-solve is
  * needed at render time. */
trait StimulusBackend[A <: SolvedArtifact]:
  /** e.g. "gas-asm", "chiselsim". */
  def kind: String

  /** Produce the concrete stimulus artifact (assembly text, a ChiselSim
    * script, …) from a solved artifact. */
  def render(solved: A): String
