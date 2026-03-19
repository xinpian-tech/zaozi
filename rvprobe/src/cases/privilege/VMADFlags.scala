// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.privilege

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.privilege.PrivilegeProbeLib.*
import me.jiuyang.rvprobe.cases.privilege.{CSR, Cause}

// A=0: first access causes page fault (hardware doesn't set A bit automatically on all impls).
@main def VMADAccessBit(outputPath: String): Unit =
  object VMADAccessBit extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap("trap_handler_rec")
      trapHandlerWithRecord()
      pmpOpenAll()

      // identity map gigapage with A=0: V|R|W|X|D (no A) = 0x01|0x02|0x04|0x08|0x80 = 0x8f
      la(x5, "pgtbl")
      li(x6, (0x80000L << 10) | 0x8fL)
      sd(x5, x6, 16)

      enableSv39()
      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      lw(x11, x10, 0) // access with A=0 — should page fault (cause=13 load page fault)
      ecall()

      label("m_check")
      la(x10, "trap_cause")
      ld(x11, x10, 0)
      addi(x12, x0, Cause.LOAD_PAGE_FAULT)
      bne(x11, x12, "fail")
      j("exit")

      label("fail")
      la(x6, "tohost")
      sd(x6, x0, 0)
      label("spin_fail")
      j("spin_fail")

      exitSeq()
      tohostSection()
      pageTableData()
      trapResultData()

      section(".data")
      balign(64)
      label("buf")
      zero(64)
  VMADAccessBit.emit(outputPath)

// D=0, A=1: lw succeeds (read doesn't need D), sw causes store page fault (15).
@main def VMADDirtyBit(outputPath: String): Unit =
  object VMADDirtyBit extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap("trap_handler_rec")
      trapHandlerWithRecord()
      pmpOpenAll()

      // identity map gigapage with A=1, D=0: V|R|W|X|A (no D) = 0x01|0x02|0x04|0x08|0x40 = 0x4f
      la(x5, "pgtbl")
      li(x6, (0x80000L << 10) | 0x4fL)
      sd(x5, x6, 16)

      enableSv39()
      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      lw(x11, x10, 0) // load — should succeed (A=1, read doesn't need D)
      sw(x10, x11, 0) // store — should trap (D=0, cause=15 store page fault)
      ecall()

      label("m_check")
      la(x10, "trap_cause")
      ld(x11, x10, 0)
      addi(x12, x0, Cause.STORE_PAGE_FAULT)
      bne(x11, x12, "fail")
      j("exit")

      label("fail")
      la(x6, "tohost")
      sd(x6, x0, 0)
      label("spin_fail")
      j("spin_fail")

      exitSeq()
      tohostSection()
      pageTableData()
      trapResultData()

      section(".data")
      balign(64)
      label("buf")
      zero(64)
  VMADDirtyBit.emit(outputPath)
