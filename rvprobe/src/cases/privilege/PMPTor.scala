// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.privilege

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.privilege.PrivilegeProbeLib.*
import me.jiuyang.rvprobe.cases.privilege.{CSR, Cause}

// TOR basic: pmpaddr0=base>>2, pmpaddr1=top>>2, entry1 cfg=TOR+RWX. S-mode lw within [base,top) succeeds.
@main def PMPTorBasic(outputPath: String): Unit =
  object PMPTorBasic extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap()
      trapHandler()

      // entry0: pmpaddr0 = buf_base >> 2 (TOR lower bound)
      la(x10, "buf")
      srli(x10, x10, 2)
      csrrw(x0, x10, CSR.PMPADDR0)

      // entry1: pmpaddr1 = (buf_base + 4096) >> 2 (TOR upper bound)
      la(x10, "buf")
      li(x11, 4096)
      add(x10, x10, x11)
      srli(x10, x10, 2)
      csrrw(x0, x10, CSR.PMPADDR1)

      // entry2: NAPOT all space for code execution
      addi(x5, x0, -1)
      csrrw(x0, x5, CSR.PMPADDR2)

      // pmpcfg0: entry0=0x00 (OFF), entry1=0x0f (TOR+RWX), entry2=0x1f (NAPOT+RWX)
      li(x5, 0x1f0f00L)
      csrrw(x0, x5, CSR.PMPCFG0)

      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      lw(x11, x10, 0) // load within [base, top) — should succeed
      j("exit")

      finish()

      section(".data")
      balign(4096)
      label("buf")
      zero(4096)
  PMPTorBasic.emit(outputPath)

// TOR boundary precision: access at base succeeds, access at top (just outside) traps.
@main def PMPTorBoundary(outputPath: String): Unit =
  object PMPTorBoundary extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap("trap_handler_rec")
      trapHandlerWithRecord()

      // entry0: pmpaddr0 = buf_base >> 2
      la(x10, "buf")
      srli(x10, x10, 2)
      csrrw(x0, x10, CSR.PMPADDR0)

      // entry1: pmpaddr1 = (buf_base + 64) >> 2 — small TOR region, 64 bytes
      la(x10, "buf")
      addi(x10, x10, 64)
      srli(x10, x10, 2)
      csrrw(x0, x10, CSR.PMPADDR1)

      // entry2: NAPOT 4KB covering code region around _start, for S-mode instruction fetch
      la(x5, "_start")
      srli(x5, x5, 2)
      ori(x5, x5, 0x1ff)
      csrrw(x0, x5, CSR.PMPADDR2)

      // pmpcfg0: entry0=0x00, entry1=0x0f (TOR+RWX for buf window), entry2=0x1f (NAPOT+RWX for .text)
      li(x5, 0x1f0f00L)
      csrrw(x0, x5, CSR.PMPCFG0)

      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      lw(x11, x10, 0) // access at base — should succeed

      // access at top (offset 64, just outside TOR region)
      la(x10, "buf")
      addi(x10, x10, 64)
      lw(x11, x10, 0) // should trap (cause=5 load access fault)
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
  PMPTorBoundary.emit(outputPath)
