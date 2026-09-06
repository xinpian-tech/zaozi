// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.frontendlib

/** A DUT frontend describes its stimulus alphabet and optional internal signals, solves a recipe, and renders the
  * result through a matching backend.
  *
  * Each frontend owns its artifact type so it can retain the information needed to render without solving again. For
  * example, the RISC-V frontend retains the assembly statement layout alongside the solved instruction fields.
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

  /** Solve the DUT's recipe/spec into its artifact (the SMT core's output plus any design-specific structure the
    * backend needs).
    */
  def solve(): Artifact

  /** Backend that renders an already-solved artifact — no re-solve. */
  def backend: StimulusBackend[Artifact]

  /** End-to-end: solve once, then render. */
  final def generate(): String = backend.render(solve())

/** The frontend-agnostic output of the SMT core: a solved stimulus sequence.
  *
  * @param selections
  *   sequence index -> chosen alphabet element id
  * @param fields
  *   solved field name (e.g. "rs1_3", "imm12_8") -> value
  */
final case class SolvedSequence(
  selections: Map[Int, Int],
  fields:     Map[String, BigInt])

/** A frontend's fully-solved artifact: the frontend-agnostic [[SolvedSequence]] plus whatever design-specific structure
  * its backend needs to render (for the RISC-V leg, the recipe statement layout). Backends render from this, so no
  * stage is re-run at render time.
  */
trait SolvedArtifact:
  def sequence: SolvedSequence

/** One atomic stimulus kind in a DUT's alphabet (an instruction opcode, a transaction kind, …). Its legality is
  * expressed as constraints over the shared SMT layer by the owning frontend.
  */
trait StimulusKind:
  /** Stable id used in [[SolvedSequence.selections]]. */
  def id: Int

  /** Human-readable mnemonic (instruction name, transaction kind, …). */
  def mnemonic: String

/** The set of atomic stimuli a DUT accepts. */
trait StimulusAlphabet:
  def kinds: Seq[StimulusKind]

/** A design-internal microarchitectural signal exposed as a first-class constraint dimension (from a Chisel/Zaozi
  * Object Model or a Verilog annotation). `category` groups related signals (e.g. an execution-unit class), enabling
  * the architecture-x-microarchitecture constraint matrix.
  */
trait WhiteboxPredicate:
  def signal:   String
  def category: String

/** Renders a frontend's solved artifact into runnable stimulus for the DUT's world. Parameterized by the frontend's
  * artifact type so no re-solve is needed at render time.
  */
trait StimulusBackend[A <: SolvedArtifact]:
  /** e.g. "gas-asm", "chiselsim". */
  def kind: String

  /** Produce the concrete stimulus artifact (assembly text, a ChiselSim script, …) from a solved artifact.
    */
  def render(solved: A): String
