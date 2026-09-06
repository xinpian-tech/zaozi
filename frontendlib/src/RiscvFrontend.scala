// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.frontendlib

import me.jiuyang.rvprobe.{getMergedInstructions, RVGenerator, SolvedRecipe, Statement}

/** The RISC-V leg's solved artifact: the frontend-agnostic [[SolvedSequence]] plus the recipe statement layout the GAS
  * backend needs to render.
  */
final case class RiscvArtifact(
  sequence:   SolvedSequence,
  statements: Seq[Statement])
    extends SolvedArtifact

/** The RISC-V leg of the [[DutFrontend]] contract — the reference implementation that validates the seam can carry a
  * real, fully-wired frontend.
  *
  * It adapts the existing [[RVGenerator]] machinery:
  *   - `alphabet` — the RISC-V instruction set from riscv-opcodes; each merged variant becomes a [[StimulusKind]] whose
  *     `id` is the opcode index the generated constraints use and whose `mnemonic` is the instruction name.
  *   - `whitebox` — empty for a plain core. The T1/Chisel leg populates this from the Object Model (the
  *     architecture-x-microarchitecture matrix).
  *   - `solve` / `backend` — solve the recipe once into a [[RiscvArtifact]] (via [[RVGenerator.solveRecipe]]), then
  *     render it to GAS assembly (via `renderRecipeAsm`). No stage is re-run at render time.
  */
final class RiscvFrontend(gen: RVGenerator) extends DutFrontend:
  type Artifact = RiscvArtifact

  def name: String = gen.name

  lazy val alphabet: StimulusAlphabet = new StimulusAlphabet:
    lazy val kinds: Seq[StimulusKind] =
      getMergedInstructions().zipWithIndex.map { case (instr, idx) =>
        new StimulusKind:
          def id:       Int    = idx
          def mnemonic: String = instr.name
      }

  def solve(): RiscvArtifact =
    val recipe = gen.solveRecipe()
    RiscvArtifact(SolvedSequence(recipe.opcodes, recipe.args), recipe.statements)

  def backend: StimulusBackend[RiscvArtifact] = new StimulusBackend[RiscvArtifact]:
    def kind:                          String = "gas-asm"
    def render(solved: RiscvArtifact): String =
      gen.renderRecipeAsm(SolvedRecipe(solved.sequence.selections, solved.sequence.fields, solved.statements))
