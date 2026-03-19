// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.privilege

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.privilege.PrivilegeProbeLib.*
import me.jiuyang.rvprobe.cases.privilege.{CSR, Cause}

// Sv39 identity-mapped gigapage: lw/sw through virtual translation succeed.
@main def VMSv39Identity(outputPath: String): Unit =
  object VMSv39Identity extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap()
      trapHandler()
      pmpOpenAll()

      // configure page table: identity map for 0x80000000 1GB gigapage
      la(x5, "pgtbl")
      li(x6, (0x80000L << 10) | 0xcfL) // V|R|W|X|A|D + PPN
      sd(x5, x6, 16)                   // pgtbl[2] (VPN[2]=2 for 0x80000000)

      enableSv39()
      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      lw(x11, x10, 0) // load through translation
      sw(x10, x11, 0) // store through translation
      j("exit")

      exitSeq()
      tohostSection()
      pageTableData()

      section(".data")
      balign(64)
      label("buf")
      zero(64)
  VMSv39Identity.emit(outputPath)

// Sv39 identity map: write value then read back, verify consistency.
@main def VMSv39LoadStore(outputPath: String): Unit =
  object VMSv39LoadStore extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap()
      trapHandler()
      pmpOpenAll()

      // identity map gigapage
      la(x5, "pgtbl")
      li(x6, (0x80000L << 10) | 0xcfL)
      sd(x5, x6, 16)

      enableSv39()
      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      li(x11, 0xdeadbeefL)
      sw(x10, x11, 0) // write value
      lw(x12, x10, 0) // read back
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

      section(".data")
      balign(64)
      label("buf")
      zero(64)
  VMSv39LoadStore.emit(outputPath)
