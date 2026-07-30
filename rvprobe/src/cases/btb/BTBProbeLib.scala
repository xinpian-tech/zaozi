// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.btb

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.HTIFLib

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Reusable helpers for BTB (Branch Target Buffer) verification probes.
  *
  * These probes test that the frontend correctly handles branch prediction for non-branch instructions that may alias
  * with BTB entries. The key bug pattern: after a `jal` trains the BTB, a subsequent non-branch instruction at an
  * aliasing PC is mis-predicted as a taken branch, corrupting mepc with a speculative target address.
  */
object BTBProbeLib:

  def btbSets(): Seq[Recipe ?=> SetConstraint] =
    isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())

  /** Emit N nop instructions for padding (to control PC alignment and BTB aliasing). */
  def nopSled(
    n: Int
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    for _ <- 0 until n do addi(x0, x0, 0)

  /** Emit a `.data` section with a page-aligned code trampoline buffer.
    *
    * Used for indirect jumps to known addresses to train the BTB.
    */
  def trampolineData(
    name: String = "trampoline",
    size: Int = 4096
  )(
    using Recipe
  ): Unit =
    section(".data")
    balign(4096)
    label(name)
    zero(size)
