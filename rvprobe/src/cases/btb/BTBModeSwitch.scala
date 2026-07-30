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

// BTB across privilege transition: M-mode jal trains BTB, then mret to S-mode.
// S-mode code at a PC that may alias with the M-mode jal target.
// The original program.S bug: jal ra, switch_to_s_mode; lui x11, 0x40000
// where lui's PC aliases with the jal BTB entry after mret.
@main def BTBMretAlias(outputPath: String): Unit =
  object BTBMretAlias extends RVGenerator:
    val sets          = btbSets()
    def constraints() =
      textStartWithTrap()
      pmpOpenAll()
      mapGigapageIdentity(0xcfL)
      enableSv39()

      // M-mode: jal to a subroutine (trains BTB), then switch to S-mode
      jal(x1, "m_subroutine")
      // After return from subroutine, switch to S-mode
      // The S-mode entry point is at a PC that follows the jal fall-through path.
      switchToSMode("s_code")

      label("m_subroutine")
      // Do something trivial, then return
      addi(x10, x0, 42)
      jalr(x0, x1, 0)

      label("s_code")
      // lui immediately after mode switch — this is the critical pattern from program.S
      lui(x11, 0x40)
      // If BTB mis-predicts lui, we'll get INSN_PAGE_FAULT instead of reaching here
      j("exit")

      finish()
      pageTableData()
  BTBMretAlias.emit(outputPath)

// BTB across ecall/mret: S-mode ecall trains BTB around the ecall PC,
// then after mret back to S-mode, the instruction following ecall must execute correctly.
@main def BTBEcallReturn(outputPath: String): Unit =
  object BTBEcallReturn extends RVGenerator:
    val sets          = btbSets()
    def constraints() =
      textStartWithTrap()
      pmpOpenAll()
      mapGigapageIdentity(0xcfL)
      enableSv39()
      switchToSMode("s_code")

      label("s_code")
      addi(x20, x0, 1)
      // ecall traps to M-mode; trap handler does mepc+4 and mret back
      ecall()
      // This instruction must execute after mret — BTB must not redirect it
      addi(x20, x20, 1)
      // If we reach here, ecall/mret didn't corrupt BTB
      j("exit")

      finish()
      pageTableData()
  BTBEcallReturn.emit(outputPath)

// BTB with nop sled: insert nop padding between jal and non-branch to vary the PC offset
// and test different BTB index/tag aliasing patterns.
@main def BTBNopPadding(outputPath: String): Unit =
  object BTBNopPadding extends RVGenerator:
    val sets          = btbSets()
    def constraints() =
      textStartWithTrap()
      pmpOpenAll()
      mapGigapageIdentity(0xcfL)
      enableSv39()
      switchToSMode("s_code")

      label("s_code")
      // Phase 1: jal with 0 nops before lui
      jal(x1, "pad_target_a")
      lui(x10, 0x11)

      // Phase 2: jal with 4 nops before lui (different PC alignment)
      jal(x1, "pad_target_b")
      nopSled(4)
      lui(x10, 0x22)

      // Phase 3: jal with 8 nops before lui
      jal(x1, "pad_target_c")
      nopSled(8)
      lui(x10, 0x33)

      // If all three phases executed without BTB mis-prediction, we reach exit
      j("exit")

      label("pad_target_a")
      jalr(x0, x1, 0)
      label("pad_target_b")
      jalr(x0, x1, 0)
      label("pad_target_c")
      jalr(x0, x1, 0)

      finish()
      pageTableData()
  BTBNopPadding.emit(outputPath)
