// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.privilege

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.privilege.PrivilegeProbeLib.*
import me.jiuyang.rvprobe.cases.privilege.{CSR, Cause}

// R-only region: lw succeeds, sw traps (cause=7).
@main def PMPReadOnly(outputPath: String): Unit =
  object PMPReadOnly extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap("trap_handler_rec")
      trapHandlerWithRecord()

      // entry0: NAPOT covering buf, R only (cfg=0x19: L=0, A=NAPOT=11, R=1, W=0, X=0)
      la(x10, "buf")
      srli(x10, x10, 2)
      ori(x10, x10, 0x1ff)
      csrrw(x0, x10, CSR.PMPADDR0)

      // entry1: NAPOT all space, RWX
      addi(x5, x0, -1)
      csrrw(x0, x5, CSR.PMPADDR1)

      // pmpcfg0: entry0=0x19 (NAPOT+R), entry1=0x1f (NAPOT+RWX)
      li(x5, 0x1f19L)
      csrrw(x0, x5, CSR.PMPCFG0)

      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      lw(x11, x10, 0) // load — should succeed
      sw(x10, x11, 0) // store — should trap (cause=7)
      ecall()

      label("m_check")
      la(x10, "trap_cause")
      ld(x11, x10, 0)
      addi(x12, x0, Cause.STORE_ACCESS_FAULT)
      bne(x11, x12, "fail")
      j("exit")

      fail()

      finish()
      trapResultData()

      section(".data")
      balign(4096)
      label("buf")
      zero(4096)
  PMPReadOnly.emit(outputPath)

// WX configuration (R=0,W=1 is reserved in RISC-V spec): test implementation behavior.
@main def PMPWriteExecute(outputPath: String): Unit =
  object PMPWriteExecute extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap("trap_handler_rec")
      trapHandlerWithRecord()

      // entry0: NAPOT covering buf, W+X (cfg=0x1e: A=NAPOT, R=0, W=1, X=1) — reserved combo
      la(x10, "buf")
      srli(x10, x10, 2)
      ori(x10, x10, 0x1ff)
      csrrw(x0, x10, CSR.PMPADDR0)

      // entry1: NAPOT all space, RWX
      addi(x5, x0, -1)
      csrrw(x0, x5, CSR.PMPADDR1)

      // pmpcfg0: entry0=0x1e (NAPOT+WX), entry1=0x1f (NAPOT+RWX)
      li(x5, 0x1f1eL)
      csrrw(x0, x5, CSR.PMPCFG0)

      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      lw(x11, x10, 0) // load — behavior is implementation-defined (R=0,W=1 reserved)
      ecall()

      label("m_check")
      // just exit, we're testing that we don't hang
      j("exit")

      finish()
      trapResultData()

      section(".data")
      balign(4096)
      label("buf")
      zero(4096)
  PMPWriteExecute.emit(outputPath)

// No access: no permissions at all, any access traps.
@main def PMPNoAccess(outputPath: String): Unit =
  object PMPNoAccess extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap("trap_handler_rec")
      trapHandlerWithRecord()

      // entry0: NAPOT covering buf, no permissions (cfg=0x18: A=NAPOT, R=0, W=0, X=0)
      la(x10, "buf")
      srli(x10, x10, 2)
      ori(x10, x10, 0x1ff)
      csrrw(x0, x10, CSR.PMPADDR0)

      // entry1: NAPOT all space, RWX
      addi(x5, x0, -1)
      csrrw(x0, x5, CSR.PMPADDR1)

      // pmpcfg0: entry0=0x18 (NAPOT, no perms), entry1=0x1f (NAPOT+RWX)
      li(x5, 0x1f18L)
      csrrw(x0, x5, CSR.PMPCFG0)

      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      lw(x11, x10, 0) // load — should trap (cause=5)
      ecall()

      label("m_check")
      la(x10, "trap_cause")
      ld(x11, x10, 0)
      addi(x12, x0, Cause.LOAD_ACCESS_FAULT)
      bne(x11, x12, "fail")
      j("exit")

      fail()

      finish()
      trapResultData()

      section(".data")
      balign(4096)
      label("buf")
      zero(4096)
  PMPNoAccess.emit(outputPath)
