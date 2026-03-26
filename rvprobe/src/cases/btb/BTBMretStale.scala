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

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

// =============================================================================
// BTB Stale-After-Mret Test Suite
//
// Bug pattern: `jal ra, fn` trains BTB → `fn` does `csrw mepc, ra; mret` →
// mret returns to jal+4 in S-mode → BTB still has the jal entry → frontend
// mis-predicts jal+4 as a taken branch → speculative redirect to invalid VA →
// INSN_PAGE_FAULT with corrupted mepc.
//
// Three trigger requirements (ALL must hold):
//   1. A branch (jal) trains the BTB at PC X
//   2. mret sets PC = X+4 (the branch's fall-through), not a separate label
//   3. BTB state is NOT invalidated by the mret pipeline flush
//
// Variants explore: instruction at fall-through, nop padding, FP context.
// =============================================================================

/** Shared helpers for the mret-stale BTB test variants. */
object MretStaleLib:

  /** Prologue: trap handler + optional FP enable + PMP open. */
  def prologue(
    enableFP: Boolean = true
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    textStart()
    la(x5, "trap_handler")
    csrrw(x0, x5, CSR.MTVEC)
    if enableFP then
      csrrs(x5, x0, CSR.MSTATUS)
      li(x6, 0x3000L)
      or(x5, x5, x6)
      csrrw(x0, x5, CSR.MSTATUS)
      csrrw(x0, x0, 0x003)
      csrrw(x0, x0, CSR.MSCRATCH)
    pmpOpenAll()

  /** Subroutine: configure Sv39 + mret to ra (= jal+4) in S-mode. */
  def switchToSModeViaRa(
    subrLabel: String = "switch_to_s_mode"
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    align(2)
    label(subrLabel)
    la(x5, "pgtbl")
    li(x6, (0x80000L << 10) | 0xcfL)
    sd(x5, x6, 16)
    addi(x7, x0, 8)
    slli(x7, x7, 60)
    srli(x6, x5, 12)
    or(x7, x7, x6)
    csrrw(x0, x7, CSR.SATP)
    sfenceVma(x0, x0)
    li(x5, ~(3L << 11))
    csrrs(x6, x0, CSR.MSTATUS)
    and(x6, x6, x5)
    li(x7, 1L << 11)
    or(x6, x6, x7)
    csrrw(x0, x6, CSR.MSTATUS)
    csrrw(x0, x1, CSR.MEPC) // mepc = ra = jal + 4
    mret()

  /** Epilogue: exit_s_mode stub + trap handler + page table + tohost. */
  def epilogue(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    align(2)
    label("exit_s_mode")
    ecall()
    jalr(x0, x1, 0)
    trapHandler("trap_handler")
    pageTableData()
    tohostSection()

  /** HTIF exit with value=1 (matching program.S convention). */
  def exitPass(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    label("exit")
    li(x5, 1L)
    la(x6, "tohost")
    sd(x6, x5, 0)
    label("spin")
    j("spin")

import MretStaleLib.*

// Variant A: Original bug — FP ops + jal → lui + ld(unmapped) at fall-through.
@main def BTBMretStaleLuiLd(outputPath: String): Unit =
  object BTBMretStaleLuiLd extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVFZFA(), isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      prologue()
      j("user_code")
      label("user_code")
      li(x8, 0x774492720dbedb91L)
      fmvDX(x16, x8)
      li(x8, 0x271141afdb5a2f58L)
      fmvDX(x17, x8)
      froundS(x17, 7, x16)
      csrrs(x14, x0, 0x001)
      jal(x1, "switch_to_s_mode")
      // fall-through in S-mode: BTB may mis-predict lui
      lui(x11, 0x40000)
      ld(x12, x11, 0) // 0x40000000 unmapped → page fault
      fsubS(x16, 1, x16, x17)
      froundS(x17, 7, x16)
      exitPass()
      switchToSModeViaRa()
      epilogue()
  BTBMretStaleLuiLd.emit(outputPath)

// Variant B: lui only (no unmapped ld). Isolates BTB corruption from page fault cascade.
@main def BTBMretStaleLui(outputPath: String): Unit =
  object BTBMretStaleLui extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVFZFA(), isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      prologue()
      j("user_code")
      label("user_code")
      li(x8, 0x774492720dbedb91L)
      fmvDX(x16, x8)
      li(x8, 0x271141afdb5a2f58L)
      fmvDX(x17, x8)
      froundS(x17, 7, x16)
      jal(x1, "switch_to_s_mode")
      lui(x11, 0x42) // no unmapped access
      j("exit")
      exitPass()
      switchToSModeViaRa()
      epilogue()
  BTBMretStaleLui.emit(outputPath)

// Variant C: addi at fall-through. Tests that bug isn't lui-specific.
@main def BTBMretStaleAddi(outputPath: String): Unit =
  object BTBMretStaleAddi extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      prologue(enableFP = false)
      addi(x10, x0, 100)
      jal(x1, "switch_to_s_mode")
      addi(x10, x10, 1) // non-branch at fall-through
      j("exit")
      exitPass()
      switchToSModeViaRa()
      epilogue()
  BTBMretStaleAddi.emit(outputPath)

// Variant D: ld from mapped address at fall-through. Tests load instructions.
@main def BTBMretStaleLd(outputPath: String): Unit =
  object BTBMretStaleLd extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      prologue(enableFP = false)
      la(x10, "buf")
      jal(x1, "switch_to_s_mode")
      ld(x11, x10, 0) // mapped address, should succeed
      j("exit")
      exitPass()
      switchToSModeViaRa()
      epilogue()
      section(".data")
      balign(64)
      label("buf")
      zero(64)
  BTBMretStaleLd.emit(outputPath)

// Variant E: nop padding shifts fall-through to jal+36. Tests BTB index bit sensitivity.
@main def BTBMretStaleNopPad(outputPath: String): Unit =
  object BTBMretStaleNopPad extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVFZFA(), isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      prologue()
      j("user_code")
      label("user_code")
      li(x8, 0x774492720dbedb91L)
      fmvDX(x16, x8)
      li(x8, 0x271141afdb5a2f58L)
      fmvDX(x17, x8)
      froundS(x17, 7, x16)
      jal(x1, "switch_to_s_mode")
      nopSled(8) // shift fall-through to jal+36
      lui(x11, 0x40000)
      ld(x12, x11, 0)
      j("exit")
      exitPass()
      switchToSModeViaRa()
      epilogue()
  BTBMretStaleNopPad.emit(outputPath)

// Variant F: minimal — no FP, direct jal → mret → lui. Is FP context needed to trigger?
@main def BTBMretStaleMinimal(outputPath: String): Unit =
  object BTBMretStaleMinimal extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      prologue(enableFP = false)
      jal(x1, "switch_to_s_mode")
      lui(x11, 0x42)
      j("exit")
      exitPass()
      switchToSModeViaRa()
      epilogue()
  BTBMretStaleMinimal.emit(outputPath)
