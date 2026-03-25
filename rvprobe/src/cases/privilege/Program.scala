// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.privilege

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.privilege.PrivilegeProbeLib.*
import me.jiuyang.rvprobe.cases.privilege.CSR

// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.privilege.Program out/program.S
@main def Program(outputPath: String): Unit =
  object Program extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVFZFA(), isRVZICSR(), isRVSYSTEM(), isRVS())

    def constraints() =
      textStartWithTrap()

      csrrs(x5, x0, CSR.MSTATUS)
      lui(x6, 3)           // 0x3000 — FS bits
      or(x5, x5, x6)
      csrrw(x0, x5, CSR.MSTATUS)
      csrrw(x0, x0, 0x003) // csrw fcsr, x0
      csrrw(x0, x0, CSR.MSCRATCH)

      pmpOpenAll()

      j("user_code")

      label("user_code")
      li(x8, 0x774492720dbedb91L)
      fmvDX(x16, x8)
      li(x8, 0x271141afdb5a2f58L)
      fmvDX(x17, x8)

      froundS(x17, 7, x16)
      jal(x1, "switch_to_s_mode")

      lui(x11, 0x40000)
      ld(x12, x11, 0)
      fsubS(x16, 1, x16, x17)
      froundS(x17, 7, x16)

      exit()

      align(2)
      label("switch_to_s_mode")

      // configure page table: identity map for 0x80000000 1GB region
      la(x5, "pgtbl")
      li(x6, (0x80000L << 10) | 0xcfL)
      sd(x5, x6, 16)

      enableSv39()

      // set mpp to s-mode (1), prepare mret
      li(x5, ~(3L << 11))
      csrrs(x6, x0, CSR.MSTATUS)
      and(x6, x6, x5)
      li(x7, 1L << 11)
      or(x6, x6, x7)
      csrrw(x0, x6, CSR.MSTATUS)

      csrrw(x0, x1, CSR.MEPC)
      mret()

      align(2)
      label("exit_s_mode")
      ecall()
      jalr(x0, x1, 0)

      trapHandler()
      pageTableData()
      tohostSection()
  Program.emit(outputPath)
