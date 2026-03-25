// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.privilege

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.HTIFLib.*
import me.jiuyang.rvprobe.cases.privilege.PrivilegeProbeLib.*
import me.jiuyang.rvprobe.cases.privilege.{CSR, Cause}

// NAPOT region allow: entry0 covers buf RWX, entry1 covers all RWX. S-mode lw/sw succeed.
@main def PMPNapotAllow(outputPath: String): Unit =
  object PMPNapotAllow extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap()

      // entry0: NAPOT covering buf region, RWX (cfg=0x1f: A=NAPOT,R,W,X)
      // Use a large NAPOT region to cover buf: pmpaddr = (base >> 2) | (size/2 - 1) >> 2
      // For simplicity, use a 4KB NAPOT region aligned to buf
      la(x10, "buf")
      srli(x10, x10, 2)
      // NAPOT 4KB: addr bits = base[33:2] | 0x1ff (covers 4KB)
      ori(x10, x10, 0x1ff)
      csrrw(x0, x10, CSR.PMPADDR0)

      // entry1: NAPOT all space, RWX
      addi(x5, x0, -1)
      csrrw(x0, x5, CSR.PMPADDR1)

      // pmpcfg0: entry0=0x1f (NAPOT+RWX), entry1=0x1f (NAPOT+RWX)
      li(x5, 0x1f1fL)
      csrrw(x0, x5, CSR.PMPCFG0)

      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      lw(x11, x10, 0) // load — should succeed
      sw(x10, x11, 0) // store — should succeed
      ecall()

      label("m_check")
      j("exit")

      finish()

      section(".data")
      balign(4096)
      label("buf")
      zero(4096)
  PMPNapotAllow.emit(outputPath)

// NAPOT region deny write: entry0 covers buf R-only, entry1 covers all RWX. S-mode sw traps (cause=7).
@main def PMPNapotDeny(outputPath: String): Unit =
  object PMPNapotDeny extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap(recordCause = true)

      // entry0: NAPOT covering buf, R only (cfg=0x19: A=NAPOT, R=1, W=0, X=0)
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
      sw(x10, x11, 0) // store — should trap (cause=7 store access fault)
      // after trap handler returns, we end up here
      ecall()         // return to M-mode

      label("m_check")
      // verify trap_cause == 7
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
  PMPNapotDeny.emit(outputPath)

// NAPOT outside: PMP only covers code, not buf. S-mode lw buf traps (cause=5).
@main def PMPNapotOutside(outputPath: String): Unit =
  object PMPNapotOutside extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap(recordCause = true)

      // entry0: NAPOT 4KB covering only code region around _start (.text), RWX
      la(x5, "_start")
      srli(x5, x5, 2)
      ori(x5, x5, 0x1ff)
      csrrw(x0, x5, CSR.PMPADDR0)

      // Only one entry — buf is not covered by any PMP entry
      addi(x5, x0, 0x1f)
      csrrw(x0, x5, CSR.PMPCFG0)

      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      lw(x11, x10, 0) // load — should trap (cause=5 load access fault)
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
  PMPNapotOutside.emit(outputPath)
