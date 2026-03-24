// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.privilege

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.privilege.PrivilegeProbeLib.*
import me.jiuyang.rvprobe.cases.privilege.{CSR, Cause}

// Flow A: M-mode setup -> Sv39 enable -> switch to S-mode -> memory + FP smoke checks.
@main def PrivilegeSv39FpSmoke(outputPath: String): Unit =
  object PrivilegeSv39FpSmoke extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVFZFA(), isRVZICSR(), isRVSYSTEM(), isRVS())

    def constraints() =
      textStartWithTrap()

      // Enable floating-point state in mstatus (FS bits) and clear fcsr.
      csrrs(x5, x0, CSR.MSTATUS)
      lui(x6, 3)           // 0x3000 — FS bits
      or(x5, x5, x6)
      csrrw(x0, x5, CSR.MSTATUS)
      csrrw(x0, x0, 0x003) // csrw fcsr, x0
      csrrw(x0, x0, CSR.MSCRATCH)

      // Allow full memory access in M/S mode.
      pmpOpenAll()

      // configure page table: identity map for 0x80000000 1GB region
      la(x5, "pgtbl")
      li(x6, (0x80000L << 10) | 0xcfL)
      sd(x5, x6, 16)
      enableSv39()
      switchToSMode("s_code")

      label("s_code")
      // S-mode memory access smoke test under Sv39 translation.
      la(x10, "buf")
      li(x11, 0x1122334455667788L)
      sd(x10, x11, 0)
      ld(x12, x10, 0)
      bne(x11, x12, "fail")

      // S-mode floating-point smoke test.
      li(x8, 0x774492720dbedb91L)
      fmvDX(x16, x8)
      li(x8, 0x271141afdb5a2f58L)
      fmvDX(x17, x8)
      fsubS(x16, 1, x16, x17)
      froundS(x17, 7, x16)

      j("exit")

      fail()
      trapHandler()
      finish()
      pageTableData()
      section(".data")
      balign(64)
      label("buf")
      zero(64)
  PrivilegeSv39FpSmoke.emit(outputPath)

// Flow B: verify S-mode ecall traps to M-mode handler and returns to the next instruction.
@main def PrivilegeSv39EcallRoundTrip(outputPath: String): Unit =
  object PrivilegeSv39EcallRoundTrip extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())

    def constraints() =
      textStartWithTrap()
      pmpOpenAll()

      la(x5, "pgtbl")
      li(x6, (0x80000L << 10) | 0xcfL)
      sd(x5, x6, 16)
      enableSv39()
      switchToSMode("s_code")

      label("s_code")
      addi(x20, x0, 1)
      ecall()               // should trap and return to the next instruction
      addi(x20, x20, 1)
      addi(x21, x0, 2)
      bne(x20, x21, "fail")
      j("exit")

      fail()
      trapHandler()
      finish()
      pageTableData()
  PrivilegeSv39EcallRoundTrip.emit(outputPath)

// Flow C: provoke a load page fault in S-mode and verify trap-cause recording + recovery.
@main def PrivilegeSv39LoadPageFaultRecovery(outputPath: String): Unit =
  object PrivilegeSv39LoadPageFaultRecovery extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())

    def constraints() =
      textStartWithTrap("trap_handler_rec")
      pmpOpenAll()

      // map only 0x80000000 1GB region; 0x40000000 remains unmapped.
      la(x5, "pgtbl")
      li(x6, (0x80000L << 10) | 0xcfL)
      sd(x5, x6, 16)
      enableSv39()
      switchToSMode("s_code")

      label("s_code")
      li(x16, 0x40000000L)
      lw(x17, x16, 0)       // expected: load page fault (cause=13)
      label("after_fault")
      la(x12, "trap_cause")
      ld(x13, x12, 0)
      addi(x14, x0, Cause.LOAD_PAGE_FAULT)
      bne(x13, x14, "fail")
      j("exit")

      fail()
      align(2)
      label("trap_handler_rec")
      csrrs(x6, x0, CSR.MCAUSE)
      la(x28, "trap_cause")
      sd(x28, x6, 0)

      // For this case we only recover the expected load-page-fault path.
      // Unexpected causes fail fast to avoid infinite mret/retrap loops.
      addi(x7, x0, Cause.LOAD_PAGE_FAULT)
      bne(x6, x7, "fail")

      la(x5, "after_fault")
      csrrw(x0, x5, CSR.MEPC)
      csrrw(x0, x0, CSR.MCAUSE)
      csrrw(x0, x0, CSR.MTVAL)
      mret()
      finish()
      pageTableData()
      trapResultData()
  PrivilegeSv39LoadPageFaultRecovery.emit(outputPath)
