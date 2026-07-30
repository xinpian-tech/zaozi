// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.frontend

/** ============================================================================
  * DUT frontend contract — the HDL-agnostic seam of RVProbe.
  *
  * RVProbe's core is language-independent: two-stage SMT solving, the
  * sequence-level cover API (coverRAW/WAR/WAW), fresh-register allocation, and
  * lowering to the shared CIRCT/MLIR SMT dialect. What is *specific* to a design
  * is confined to a [[DutFrontend]], which contributes exactly three things:
  *
  *   1. [[DutFrontend.alphabet]] — the legal atomic stimuli and their field-level
  *      legality. For a RISC-V core this is the instruction set (opcodes +
  *      operand fields, generated from riscv-opcodes). For a Decoupled Chisel
  *      module it is the transaction kinds read off the typed IO. For raw Verilog
  *      it is a user-supplied interface description.
  *
  *   2. [[DutFrontend.whitebox]] — design-internal microarchitectural signals
  *      lifted as first-class constraint variables. For Chisel/Zaozi these come
  *      from the compile-time Object Model; for Verilog from user annotations.
  *      Empty ⇒ the DUT is treated black-box.
  *
  *   3. [[DutFrontend.backend]] — how a solved sequence is rendered into runnable
  *      stimulus for the DUT's world (RISC-V: GAS assembly; a Decoupled module:
  *      ChiselSim poke/peek/step). The lowering of DUT + constraints into CIRCT
  *      is shared across every frontend, since Chisel (via firtool), Zaozi, and
  *      Verilog (via CIRCT ImportVerilog) all land in the same IR.
  *
  * Planned legs, cleanest first (see wishing/9-rvprobe-multilang-frontend):
  *   - Zaozi   — same IR, structured metadata: the reference implementation.
  *   - Chisel  — Object Model extraction generalized beyond the T1 case.
  *   - Verilog — hardest/most valuable (no Object Model): alphabet from a
  *               supplied interface, whitebox from annotations/architectural
  *               state only. This is the leg that lets a Verilog core (e.g.
  *               cv32e40x) be targeted without rewriting it in Chisel.
  * ============================================================================
  */
trait DutFrontend:
  /** Human-readable frontend/DUT name (used in generated output and logs). */
  def name: String

  /** The stimulus alphabet this DUT accepts. */
  def alphabet: StimulusAlphabet

  /** White-box microarchitectural predicates. Empty by default (black-box). */
  def whitebox: Seq[WhiteboxPredicate] = Seq.empty

  /** Renderer that turns a solved sequence into runnable stimulus. */
  def backend: StimulusBackend

/** The output of the (frontend-agnostic) SMT core: a solved stimulus sequence.
  *
  * @param selections sequence index -> chosen alphabet element id
  * @param fields     solved field name (e.g. "rs1_3", "imm12_8") -> value
  */
final case class SolvedSequence(
  selections: Map[Int, Int],
  fields:     Map[String, BigInt]
)

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

/** Renders a [[SolvedSequence]] into runnable stimulus for the DUT's world. */
trait StimulusBackend:
  /** e.g. "gas-asm", "chiselsim". */
  def kind: String

  /** Produce the concrete stimulus artifact (assembly text, a ChiselSim
    * script, …) from a solved sequence. */
  def render(solved: SolvedSequence): String
