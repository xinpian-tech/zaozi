// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.privilege

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.privilege.PrivilegeProbeLib.*
import me.jiuyang.rvprobe.cases.privilege.{CSR, Cause}

// Read-only page: PTE R=1,W=0,X=0. lw succeeds, sw causes store page fault (15).
@main def VMReadOnlyPage(outputPath: String): Unit =
  object VMReadOnlyPage extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap("trap_handler_rec")
      trapHandlerWithRecord()
      pmpOpenAll()

      // identity map gigapage for code: root[2] = V|R|W|X|A|D
      la(x5, "pgtbl")
      li(x6, (0x80000L << 10) | 0xcfL)
      sd(x5, x6, 16)

      // map buf page as read-only: use a separate VPN
      // buf is also in the 0x80000000 region, so covered by the gigapage above
      // Instead, we use a two-level setup: root[2] -> L2, with L2 entries having different perms
      // For simplicity, we use a single gigapage with R-only for the whole region
      // and test that sw faults. Re-map root[2] as R-only:
      la(x5, "pgtbl")
      // PTE: V|R|A|D (no W, no X) = 0x01 | 0x02 | 0x40 | 0x80 = 0xc3
      li(x6, (0x80000L << 10) | 0xc3L)
      sd(x5, x6, 16)

      enableSv39()
      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      lw(x11, x10, 0) // load — should succeed (R=1)
      sw(x10, x11, 0) // store — should trap (W=0, cause=15 store page fault)
      ecall()

      label("m_check")
      la(x10, "trap_cause")
      ld(x11, x10, 0)
      addi(x12, x0, Cause.STORE_PAGE_FAULT)
      bne(x11, x12, "fail")
      j("exit")

      fail()

      finish()
      pageTableData()
      trapResultData()

      section(".data")
      balign(64)
      label("buf")
      zero(64)
  VMReadOnlyPage.emit(outputPath)

// Execute-only page: PTE X=1,R=0. Jump and execute succeeds, lw causes load page fault (13).
@main def VMExecuteOnlyPage(outputPath: String): Unit =
  object VMExecuteOnlyPage extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap("trap_handler_rec")
      trapHandlerWithRecord()
      pmpOpenAll()

      // identity map gigapage: X-only = V|X|A|D = 0x01|0x08|0x40|0x80 = 0xc9
      la(x5, "pgtbl")
      li(x6, (0x80000L << 10) | 0xc9L)
      sd(x5, x6, 16)

      enableSv39()
      switchToSMode("s_code")

      label("s_code")
      // code execution itself succeeds (X=1) since we're running from this page
      la(x10, "buf")
      lw(x11, x10, 0) // load — should trap (R=0, cause=13 load page fault)
      ecall()

      label("m_check")
      la(x10, "trap_cause")
      ld(x11, x10, 0)
      addi(x12, x0, Cause.LOAD_PAGE_FAULT)
      bne(x11, x12, "fail")
      j("exit")

      fail()

      finish()
      pageTableData()
      trapResultData()

      section(".data")
      balign(64)
      label("buf")
      zero(64)
  VMExecuteOnlyPage.emit(outputPath)

// No-execute page: PTE R=1,W=1,X=0. lw/sw succeed, jump causes insn page fault (12).
@main def VMNoExecutePage(outputPath: String): Unit =
  object VMNoExecutePage extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap("trap_handler_rec")
      trapHandlerWithRecord()
      pmpOpenAll()

      // identity map gigapage: R+W only = V|R|W|A|D = 0x01|0x02|0x04|0x40|0x80 = 0xc7
      la(x5, "pgtbl")
      li(x6, (0x80000L << 10) | 0xc7L)
      sd(x5, x6, 16)

      enableSv39()

      // We can't directly switch to S-mode and run from an X=0 page, since the code itself
      // would fault. Instead, test by jumping to a known location in buf that has instructions.
      // First, write a "ret" instruction (jalr x0, x1, 0) to buf from M-mode
      la(x10, "buf")
      li(x11, 0x00008067L) // jalr x0, ra, 0 (ret)
      sw(x10, x11, 0)

      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      lw(x11, x10, 0) // load — should succeed (R=1)
      sw(x10, x11, 0) // store — should succeed (W=1)
      // jump to buf — should trap (X=0, cause=12 insn page fault)
      la(x10, "buf")
      jalr(x1, x10, 0)
      ecall()

      label("m_check")
      la(x10, "trap_cause")
      ld(x11, x10, 0)
      addi(x12, x0, Cause.INSN_PAGE_FAULT)
      bne(x11, x12, "fail")
      j("exit")

      fail()

      finish()
      pageTableData()
      trapResultData()

      section(".data")
      balign(64)
      label("buf")
      zero(64)
  VMNoExecutePage.emit(outputPath)
