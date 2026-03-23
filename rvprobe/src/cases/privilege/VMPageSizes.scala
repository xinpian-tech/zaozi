// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.privilege

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.privilege.PrivilegeProbeLib.*
import me.jiuyang.rvprobe.cases.privilege.{CSR, Cause}

// Gigapage: 1GB single-level page table (root leaf PTE).
@main def VMGigapage(outputPath: String): Unit =
  object VMGigapage extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap()
      trapHandler()
      pmpOpenAll()

      // root[2] = leaf PTE for 1GB gigapage at 0x80000000
      la(x5, "pgtbl_root")
      li(x6, (0x80000L << 10) | 0xcfL) // V|R|W|X|A|D
      sd(x5, x6, 16)                   // index 2

      enableSv39("pgtbl_root")
      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      li(x11, 0x42L)
      sw(x10, x11, 0)
      lw(x12, x10, 0)
      bne(x11, x12, "fail")
      j("exit")

      fail()

      finish()

      section(".data")
      balign(4096)
      label("pgtbl_root")
      zero(4096)

      balign(64)
      label("buf")
      zero(64)
  VMGigapage.emit(outputPath)

// Megapage: 2MB two-level page table (root -> L2 leaf).
@main def VMMegapage(outputPath: String): Unit =
  object VMMegapage extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap()
      trapHandler()
      pmpOpenAll()

      // root[2] = non-leaf PTE pointing to pgtbl_l2
      la(x5, "pgtbl_root")
      la(x6, "pgtbl_l2")
      srli(x6, x6, 12)  // PPN
      slli(x6, x6, 10)  // shift to PTE PPN field
      ori(x6, x6, 0x01) // V=1 (non-leaf: no R/W/X)
      sd(x5, x6, 16)    // root[2] for VPN[2]=2 (0x80000000)

      // L2[0] = leaf megapage PTE for 0x80000000
      la(x5, "pgtbl_l2")
      li(x6, (0x80000L << 10) | 0xcfL) // V|R|W|X|A|D, PPN=0x80000
      sd(x5, x6, 0)                    // L2[0] for VPN[1]=0

      enableSv39("pgtbl_root")
      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      li(x11, 0x55L)
      sw(x10, x11, 0)
      lw(x12, x10, 0)
      bne(x11, x12, "fail")
      j("exit")

      fail()

      finish()

      section(".data")
      balign(4096)
      label("pgtbl_root")
      zero(4096)
      balign(4096)
      label("pgtbl_l2")
      zero(4096)

      balign(64)
      label("buf")
      zero(64)
  VMMegapage.emit(outputPath)

// 4KB page: three-level page table (root -> L2 -> L3 leaf).
@main def VM4KBPage(outputPath: String): Unit =
  object VM4KBPage extends RVGenerator:
    val sets          = isRV64GC() ++ Seq(isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      textStartWithTrap()
      trapHandler()
      pmpOpenAll()

      // root[2] = non-leaf PTE -> pgtbl_l2
      la(x5, "pgtbl_root")
      la(x6, "pgtbl_l2")
      srli(x6, x6, 12)
      slli(x6, x6, 10)
      ori(x6, x6, 0x01) // V=1 non-leaf
      sd(x5, x6, 16)    // root[2]

      // L2[0] = non-leaf PTE -> pgtbl_l3
      la(x5, "pgtbl_l2")
      la(x6, "pgtbl_l3")
      srli(x6, x6, 12)
      slli(x6, x6, 10)
      ori(x6, x6, 0x01) // V=1 non-leaf
      sd(x5, x6, 0)     // L2[0]

      // L3[0] = leaf 4KB PTE for physical page at 0x80000000
      la(x5, "pgtbl_l3")
      li(x6, (0x80000L << 10) | 0xcfL) // V|R|W|X|A|D
      sd(x5, x6, 0)                    // L3[0]

      enableSv39("pgtbl_root")
      switchToSMode("s_code")

      label("s_code")
      la(x10, "buf")
      li(x11, 0x77L)
      sw(x10, x11, 0)
      lw(x12, x10, 0)
      bne(x11, x12, "fail")
      j("exit")

      fail()

      finish()

      section(".data")
      balign(4096)
      label("pgtbl_root")
      zero(4096)
      balign(4096)
      label("pgtbl_l2")
      zero(4096)
      balign(4096)
      label("pgtbl_l3")
      zero(4096)

      balign(64)
      label("buf")
      zero(64)
  VM4KBPage.emit(outputPath)
