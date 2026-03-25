// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.privilege

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.privilege.PrivilegeProbeLib.*
import me.jiuyang.rvprobe.cases.privilege.{CSR, Cause}

// Modify PTE permissions + sfence.vma -> new permissions take effect.
// S-mode write succeeds -> ecall to M-mode -> remove W from PTE -> sfence.vma -> back to S-mode -> sw traps.
@main def VMSfenceVmaRemap(outputPath: String): Unit =
  object VMSfenceVmaRemap extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap("trap_handler_rec")
      trapHandlerWithRecord()
      pmpOpenAll()

      // identity map gigapage: V|R|W|X|A|D = 0xcf
      mapGigapageIdentity(0xcfL)

      enableSv39()
      switchToSMode("s_code_phase1")

      label("s_code_phase1")
      la(x10, "buf")
      li(x11, 0xaaL)
      sw(x10, x11, 0) // store — should succeed (W=1)
      ecall()         // return to M-mode

      // M-mode: modify PTE to remove W permission
      label("m_remap")
      la(x5, "pgtbl")
      // V|R|X|A|D (no W) = 0x01|0x02|0x08|0x40|0x80 = 0xcb
      li(x6, (0x80000L << 10) | 0xcbL)
      sd(x5, x6, 16)
      sfenceVma(x0, x0) // flush TLB
      switchToSMode("s_code_phase2")

      label("s_code_phase2")
      la(x10, "buf")
      sw(x10, x11, 0) // store — should trap now (W=0, cause=15)
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
  VMSfenceVmaRemap.emit(outputPath)

// Without sfence.vma, TLB may keep stale mapping. After sfence, access faults.
// Uses two-level page table so only the data megapage is invalidated (code megapage stays valid).
@main def VMSfenceVmaStale(outputPath: String): Unit =
  object VMSfenceVmaStale extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap("trap_handler_rec")
      trapHandlerWithRecord()
      pmpOpenAll()

      // Two-level: code megapage (full perms) + data megapage (full perms initially)
      setupCodeDataPageTable(0xcfL)

      enableSv39()
      switchToSMode("s_code_phase1")

      label("s_code_phase1")
      la(x10, "buf")
      lw(x11, x10, 0) // load — succeeds, TLB entry cached
      ecall()

      // M-mode: invalidate data megapage PTE in L2[1] (V=0)
      label("m_invalidate")
      la(x5, "pgtbl_l2")
      li(x6, 0L)    // PTE = 0 (V=0)
      sd(x5, x6, 8) // L2[1] — only data megapage; code megapage L2[0] stays valid
      // deliberately NO sfence.vma — TLB may still have old entry
      switchToSMode("s_code_phase2")

      label("s_code_phase2")
      la(x10, "buf")
      lw(x11, x10, 0) // may still succeed if TLB cached (implementation-dependent)
      ecall()

      // M-mode: now do sfence.vma
      label("m_sfence")
      sfenceVma(x0, x0)
      switchToSMode("s_code_phase3")

      label("s_code_phase3")
      la(x10, "buf")
      lw(x11, x10, 0) // should now fault (V=0, cause=13 load page fault)
      ecall()

      label("m_check")
      la(x10, "trap_cause")
      ld(x11, x10, 0)
      addi(x12, x0, Cause.LOAD_PAGE_FAULT)
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
  VMSfenceVmaStale.emit(outputPath)
