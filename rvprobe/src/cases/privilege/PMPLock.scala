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

// L=1 lock bit: M-mode is also restricted. cfg=0x99 (L|NAPOT|R), sw in M-mode traps (cause=7).
@main def PMPLockMMode(outputPath: String): Unit =
  object PMPLockMMode extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap(recordCause = true)

      // entry0: NAPOT covering buf, L=1 R-only (cfg=0x99: L=1, A=NAPOT, R=1, W=0, X=0)
      la(x10, "buf")
      srli(x10, x10, 2)
      ori(x10, x10, 0x1ff)
      csrrw(x0, x10, CSR.PMPADDR0)

      // entry1: NAPOT all space, RWX (no lock)
      addi(x5, x0, -1)
      csrrw(x0, x5, CSR.PMPADDR1)

      // pmpcfg0: entry0=0x99 (L+NAPOT+R), entry1=0x1f (NAPOT+RWX)
      li(x5, 0x1f99L)
      csrrw(x0, x5, CSR.PMPCFG0)

      // Stay in M-mode, attempt store to locked R-only region
      la(x10, "buf")
      sw(x10, x11, 0) // store in M-mode — should trap (cause=7) because L=1

      // after trap, check cause
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
  PMPLockMMode.emit(outputPath)

// Lock bit prevents modification: csrw pmpcfg0 after lock is ignored, read-back confirms original value.
@main def PMPLockModify(outputPath: String): Unit =
  object PMPLockModify extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap()

      // entry0: NAPOT covering buf, L=1 RWX (cfg=0x9f: L+NAPOT+RWX)
      la(x10, "buf")
      srli(x10, x10, 2)
      ori(x10, x10, 0x1ff)
      csrrw(x0, x10, CSR.PMPADDR0)

      // entry1: NAPOT all space, RWX
      addi(x5, x0, -1)
      csrrw(x0, x5, CSR.PMPADDR1)

      // pmpcfg0: entry0=0x9f (L+NAPOT+RWX), entry1=0x1f (NAPOT+RWX)
      li(x5, 0x1f9fL)
      csrrw(x0, x5, CSR.PMPCFG0)

      // Try to modify pmpcfg0 entry0 to remove write permission
      li(x5, 0x1f99L) // attempt to change entry0 to L+NAPOT+R
      csrrw(x0, x5, CSR.PMPCFG0)

      // Read back pmpcfg0 — entry0 should still be 0x9f (locked, unmodifiable)
      csrrs(x10, x0, CSR.PMPCFG0)
      li(x11, 0x9fL)       // expected: original value
      andi(x10, x10, 0xff) // isolate entry0 byte
      bne(x10, x11, "fail")
      j("exit")

      fail()

      finish()

      section(".data")
      balign(4096)
      label("buf")
      zero(4096)
  PMPLockModify.emit(outputPath)
