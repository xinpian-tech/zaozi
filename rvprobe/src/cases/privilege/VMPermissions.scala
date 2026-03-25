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

// Read-only page: PTE R=1,W=0,X=0. lw succeeds, sw causes store page fault (15).
// Uses two-level page table: code megapage (full perms) + data megapage (R-only).
@main def VMReadOnlyPage(outputPath: String): Unit =
  object VMReadOnlyPage extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap(recordCause = true)
      pmpOpenAll()

      // Two-level: code megapage (full perms) + data megapage (R-only: V|R|A|D = 0xc3)
      setupCodeDataPageTable(0xc3L)

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
      pageTableDataTwoLevel()
      trapResultData()

      section(".data")
      balign(0x200000) // 2MB-align buf into the second megapage
      label("buf")
      zero(64)
  VMReadOnlyPage.emit(outputPath)

// Execute-only page: PTE X=1,R=0. Jump and execute succeeds, lw causes load page fault (13).
@main def VMExecuteOnlyPage(outputPath: String): Unit =
  object VMExecuteOnlyPage extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap(recordCause = true)
      pmpOpenAll()

      // identity map gigapage: X-only = V|X|A|D = 0xc9
      mapGigapageIdentity(0xc9L)

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
// Uses two-level page table: code megapage (full perms) + data megapage (RW, no X).
// Trap handler recovers from INSN_PAGE_FAULT via mscratch (set before switchToSMode).
@main def VMNoExecutePage(outputPath: String): Unit =
  object VMNoExecutePage extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap(recordCause = true)
      pmpOpenAll()

      // Two-level: code megapage (full perms) + data megapage (RW, no X: V|R|W|A|D = 0xc7)
      setupCodeDataPageTable(0xc7L)

      enableSv39()

      // Write a "ret" instruction to buf from M-mode for the jalr test below
      la(x10, "buf")
      li(x11, 0x00008067L) // jalr x0, ra, 0 (ret)
      sw(x10, x11, 0)

      // Save recovery address for INSN_PAGE_FAULT trap handler (mscratch-based recovery)
      la(x5, "m_check")
      csrrw(x0, x5, CSR.MSCRATCH)

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
      pageTableDataTwoLevel()
      trapResultData()

      section(".data")
      balign(0x200000) // 2MB-align buf into the second megapage
      label("buf")
      zero(64)
  VMNoExecutePage.emit(outputPath)
