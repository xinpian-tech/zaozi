// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.btb

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.HTIFLib.*
import me.jiuyang.rvprobe.cases.privilege.PrivilegeProbeLib.*
import me.jiuyang.rvprobe.cases.privilege.{CSR, Cause}

// Faithful DSL rewrite of the original program.S that triggers BOOM v3 BTB aliasing bug.
//
// The critical pattern: M-mode code does FP ops, then `jal ra, switch_to_s_mode`.
// switch_to_s_mode configures Sv39 paging and does `csrw mepc, ra; mret` — returning
// to S-mode at the instruction AFTER the jal. At that PC, `lui x11, 0x40000` is
// mis-predicted by the BTB as a taken branch (aliasing with the jal BTB entry),
// causing speculative redirect to an invalid VA → INSN_PAGE_FAULT with corrupted mepc.
//
// Key differences from other BTB probes:
// - switch_to_s_mode is a FUNCTION called via jal/ra, not a mret-to-label
// - lui + ld at the fall-through PC after jal (the exact triggering sequence)
// - FP instructions before jal contribute to the BTB training context
@main def BTBOriginalBug(outputPath: String): Unit =
  object BTBOriginalBug extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVFZFA(), isRVZICSR(), isRVSYSTEM(), isRVS())
    def constraints() =
      // --- Trap handler (same logic as program.S) ---
      textStart()
      la(x5, "trap_handler")
      csrrw(x0, x5, CSR.MTVEC)

      // --- Enable FP state ---
      csrrs(x5, x0, CSR.MSTATUS)
      li(x6, 0x3000L) // FS = Dirty (0b11)
      or(x5, x5, x6)
      csrrw(x0, x5, CSR.MSTATUS)
      csrrw(x0, x0, 0x003) // csrw fcsr, x0
      csrrw(x0, x0, CSR.MSCRATCH)

      // --- PMP: open all ---
      pmpOpenAll()

      j("user_code")

      // --- User code: FP ops then jal to switch_to_s_mode ---
      label("user_code")
      li(x8, 0x774492720dbedb91L)
      fmvDX(x16, x8)
      li(x8, 0x271141afdb5a2f58L)
      fmvDX(x17, x8)
      froundS(x17, 7, x16)      // fround.s f17, f16, dyn
      csrrs(x14, x0, 0x001)     // csrrs x14, fflags, x0

      // THE CRITICAL JAL: trains BTB with this PC → switch_to_s_mode target
      jal(x1, "switch_to_s_mode")

      // FALL-THROUGH after mret returns here in S-mode.
      // BTB may mis-predict this lui as a taken branch (aliasing with jal above).
      lui(x11, 0x40000)
      ld(x12, x11, 0) // access 0x40000000 (unmapped → page fault, handled by trap handler)
      fsubS(x16, 1, x16, x17) // fsub.s f16, f16, f17, rtz
      froundS(x17, 7, x16)    // fround.s f17, f16, dyn

      // exit: HTIF pass (value = 1)
      label("exit")
      li(x5, 1L)
      la(x6, "tohost")
      sd(x6, x5, 0)
      label("spin")
      j("spin")

      // --- switch_to_s_mode: called via jal ra, returns via mret to ra (S-mode) ---
      align(2)
      label("switch_to_s_mode")
      // configure page table: identity map 0x80000000 1GB gigapage
      la(x5, "pgtbl")
      li(x6, (0x80000L << 10) | 0xcfL)
      sd(x5, x6, 16)

      // satp = (mode=8 << 60) | ppn(pgtbl)
      addi(x7, x0, 8)
      slli(x7, x7, 60)
      srli(x6, x5, 12)
      or(x7, x7, x6)
      csrrw(x0, x7, CSR.SATP)
      sfenceVma(x0, x0)

      // set MPP = S-mode (1)
      li(x5, ~(3L << 11))
      csrrs(x6, x0, CSR.MSTATUS)
      and(x6, x6, x5)
      li(x7, 1L << 11)
      or(x6, x6, x7)
      csrrw(x0, x6, CSR.MSTATUS)

      // mret to ra (the instruction after jal) in S-mode
      csrrw(x0, x1, CSR.MEPC)
      mret()

      // --- exit_s_mode stub ---
      align(2)
      label("exit_s_mode")
      ecall()
      jalr(x0, x1, 0)

      // --- Trap handler (matching program.S logic exactly) ---
      trapHandler("trap_handler")

      // --- Data sections ---
      pageTableData()
      tohostSection()
  BTBOriginalBug.emit(outputPath)
