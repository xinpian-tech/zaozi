// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.frontend

import me.jiuyang.rvprobe.{RVGenerator, getMergedInstructions}

/** The RISC-V leg of the [[DutFrontend]] contract — the reference implementation
  * that validates the seam can carry a real, fully-wired frontend.
  *
  * It adapts the existing [[RVGenerator]] machinery:
  *   - `alphabet` — the RISC-V instruction set from riscv-opcodes; each merged
  *     variant becomes a [[StimulusKind]] whose `id` is the opcode index the
  *     generated constraints use and whose `mnemonic` is the instruction name.
  *   - `whitebox` — empty for a plain core. The T1/Chisel leg populates this from
  *     the Object Model (the architecture-x-microarchitecture matrix).
  *   - `backend` — GAS assembly, produced through the generator's split
  *     solve/render pipeline ([[RVGenerator.solveRecipe]] / `renderRecipeAsm`).
  *
  * Wiring this leg surfaced a contract refinement: rendering RISC-V assembly
  * needs the recipe's *statement layout*, not just the [[SolvedSequence]] field
  * values. For now the RISC-V backend re-derives that layout from the recipe;
  * a later contract revision should let a frontend carry its own solved artifact
  * through render. Use [[generate]] for the efficient single-solve path.
  */
final class RiscvFrontend(gen: RVGenerator) extends DutFrontend:
  def name: String = gen.name

  lazy val alphabet: StimulusAlphabet = new StimulusAlphabet:
    lazy val kinds: Seq[StimulusKind] =
      getMergedInstructions().zipWithIndex.map { case (instr, idx) =>
        new StimulusKind:
          def id:       Int    = idx
          def mnemonic: String = instr.name
      }

  def backend: StimulusBackend = new StimulusBackend:
    def kind: String = "gas-asm"

    /** Contract-conformant render from a solved sequence. The RISC-V statement
      * layout is re-derived from the recipe (see class note); prefer
      * [[RiscvFrontend.generate]] to avoid the extra solve. */
    def render(solved: SolvedSequence): String =
      val (_, statements) = gen.solveRecipe()
      gen.renderRecipeAsm(solved, statements)

  /** End-to-end: solve the recipe once and render it to GAS assembly. */
  def generate(): String =
    val (solved, statements) = gen.solveRecipe()
    gen.renderRecipeAsm(solved, statements)
