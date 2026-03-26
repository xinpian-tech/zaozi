// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.btb

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.HTIFLib.*
import me.jiuyang.rvprobe.cases.privilege.PrivilegeProbeLib.*
import me.jiuyang.rvprobe.cases.privilege.{CSR, Cause}
import me.jiuyang.rvprobe.cases.btb.BTBProbeLib.*
import me.jiuyang.rvprobe.cases.btb.MretStaleLib

// =============================================================================
// Reusable Fragments for BTB testing
// =============================================================================
object BTBFragments:

  /** FP stress: load two double values into FP regs and do fround/fsub.
    *
    * Uses freshReg for integer scratch. Fixed: x16 (f16), x17 (f17) for FP operands.
    */
  val fpStress = Fragment(fixedRegs = Set(x16, x17)) {
    val tmp = freshReg()
    li(tmp, 0x774492720dbedb91L)
    fmvDX(x16, tmp)
    li(tmp, 0x271141afdb5a2f58L)
    fmvDX(x17, tmp)
    froundS(x17, 7, x16)
  }

  /** FP post-ops: fsub + fround after the BTB trigger point.
    *
    * Keeps the pipeline busy with FP operations while the BTB aliasing may occur.
    */
  val fpPostOps = Fragment(fixedRegs = Set(x16, x17)) {
    fsubS(x16, 1, x16, x17)
    froundS(x17, 7, x16)
  }

  /** BTB trigger: jal → mret → lui + ld(unmapped).
    *
    * The core bug pattern. lui at jal fall-through may be mis-predicted by stale BTB.
    */
  val btbLuiLd = Fragment(fixedRegs = Set(x1, x11, x12)) {
    jal(x1, "switch_to_s_mode")
    lui(x11, 0x40000)
    ld(x12, x11, 0) // 0x40000000 unmapped → page fault
  }

  /** BTB trigger: jal → mret → lui only (no unmapped access). */
  val btbLui = Fragment(fixedRegs = Set(x1, x11)) {
    jal(x1, "switch_to_s_mode")
    lui(x11, 0x42)
  }

  /** Integer ALU stress: chain of addi operations using freshRegs. */
  val aluStress = Fragment(fixedRegs = Set()) {
    val a = freshReg()
    val b = freshReg()
    val c = freshReg()
    addi(a, x0, 100)
    addi(b, a, 200)
    addi(c, b, 300)
    add(a, b, c)
  }

  /** CSR read stress: read mstatus, mcause, fflags. */
  val csrReads = Fragment(fixedRegs = Set(x14)) {
    val tmp = freshReg()
    csrrs(tmp, x0, CSR.MSTATUS)
    csrrs(x14, x0, 0x001) // fflags
  }

import BTBFragments.*

// =============================================================================
// Composed test cases
// =============================================================================

// FP stress + BTB lui+ld: the exact program.S bug pattern, composed from fragments.
// The FP instructions create pipeline pressure that may be needed to trigger the BTB aliasing.
@main def BTBComposedFpLuiLd(outputPath: String): Unit =
  object BTBComposedFpLuiLd extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVFZFA(), isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      MretStaleLib.prologue()
      compose(fpStress, btbLuiLd, fpPostOps)
      finish()
      MretStaleLib.switchToSModeViaRa()
      MretStaleLib.epilogue()
  BTBComposedFpLuiLd.emit(outputPath)

// FP stress + BTB lui only (no page fault cascade).
@main def BTBComposedFpLui(outputPath: String): Unit =
  object BTBComposedFpLui extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVFZFA(), isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      MretStaleLib.prologue()
      compose(fpStress, btbLui)
      j("exit")
      finish()
      MretStaleLib.switchToSModeViaRa()
      MretStaleLib.epilogue()
  BTBComposedFpLui.emit(outputPath)

// ALU stress + BTB lui+ld: tests if integer pipeline pressure also triggers the bug.
@main def BTBComposedAluLuiLd(outputPath: String): Unit =
  object BTBComposedAluLuiLd extends RVGenerator:
    val sets = btbSets()
    def constraints() =
      MretStaleLib.prologue()
      compose(aluStress, btbLuiLd)
      finish()
      MretStaleLib.switchToSModeViaRa()
      MretStaleLib.epilogue()
  BTBComposedAluLuiLd.emit(outputPath)

// CSR reads + FP + BTB: maximal pipeline diversity before the trigger.
@main def BTBComposedCsrFpLuiLd(outputPath: String): Unit =
  object BTBComposedCsrFpLuiLd extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVFZFA(), isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      MretStaleLib.prologue()
      compose(csrReads, fpStress, btbLuiLd, fpPostOps)
      finish()
      MretStaleLib.switchToSModeViaRa()
      MretStaleLib.epilogue()
  BTBComposedCsrFpLuiLd.emit(outputPath)

// No pipeline stress + BTB: baseline to confirm plain jal→mret→lui alone doesn't trigger.
@main def BTBComposedBaseline(outputPath: String): Unit =
  object BTBComposedBaseline extends RVGenerator:
    val sets = btbSets()
    def constraints() =
      MretStaleLib.prologue()
      compose(btbLuiLd)
      finish()
      MretStaleLib.switchToSModeViaRa()
      MretStaleLib.epilogue()
  BTBComposedBaseline.emit(outputPath)
