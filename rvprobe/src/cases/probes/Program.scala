// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.probes

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}

// Run with: mill rvprobe.runMain me.jiuyang.rvprobe.cases.probes.Program out/program.S
@main def Program(outputPath: String): Unit =
  object Program extends RVGenerator:
    val sets = isRV64GC() ++ Seq(isRVFZFA(), isRVZICSR(), isRVSYSTEM(), isRVS())
    // TODO: need additional sets for Zfa (fround.s), Zicsr (csr*), etc.

    def constraints() =
      section(".text")
      global("_start")

      label("_start")
      raw("    la      t0, trap_handler") // pseudo: auipc + addi, references label
      csrrw(x0, x5, 0x305)                // csrw mtvec, t0

      csrrs(x5, x0, 0x300) // csrr t0, mstatus
      // li t1, 0x00003000 — expands to lui+addi or longer sequence
      lui(x6, 3)           // lui t1, 3 (upper 20 bits: 0x3 << 12 = 0x3000)
      or(x5, x5, x6)       // or  t0, t0, t1
      csrrw(x0, x5, 0x300) // csrw mstatus, t0
      csrrw(x0, x0, 0x003) // csrw fcsr, x0
      csrrw(x0, x0, 0x340) // csrw mscratch, x0

      // PMP: NAPOT covering entire address space
      addi(x5, x0, -1)     // li t0, -1 (for rv64 this is pseudo, simplified here)
      csrrw(x0, x5, 0x3b0) // csrw pmpaddr0, t0
      addi(x5, x0, 0x1f)   // li t0, 0x1f
      csrrw(x0, x5, 0x3a0) // csrw pmpcfg0, t0

      j("user_code") // references label

      label("user_code")
      raw("    li x8, 0x774492720dbedb91") // pseudo: multi-instruction sequence
      fmvDX(x16, x8)                       // fmv.d.x f16, x8
      raw("    li x8, 0x271141afdb5a2f58") // pseudo: multi-instruction sequence
      fmvDX(x17, x8)                       // fmv.d.x f17, x8

      froundS(x17, 7, x16)                // fround.s f17, f16, dyn (rm=7=dyn)
      jal(x1, "switch_to_s_mode") // references label

      lui(x11, 0x40000)       // li x11, 0x40000000 (0x40000 << 12)
      ld(x12, x11, 0)         // ld x12, 0(x11)
      fsubS(x16, 1, x16, x17) // fsub.s f16, f16, f17, rtz (rm=1=rtz)
      froundS(x17, 7, x16)    // fround.s f17, f16, dyn

      label("exit")
      addi(x5, x0, 1)               // li t0, 1
      raw("    la      t1, tohost") // pseudo, references label
      sd(x6, x5, 0)                 // sd t0, 0(x6)
      label("1")
      j("1b")         // backward reference to local label

      align(2)
      label("switch_to_s_mode")

      // configure page table: identity map for 0x80000000 1GB region
      raw("    la      t0, pgtbl")                  // references label
      raw("    li      t1, (0x80000 << 10) | 0xCF") // complex immediate
      sd(x5, x6, 16)                                 // sd t1, 16(t0)

      // write satp to enable sv39 paging
      addi(x7, x0, 8)       // li t2, 8
      slli(x7, x7, 60) // slli t2, t2, 60
      srli(x6, x5, 12) // srli t1, t0, 12
      or(x7, x7, x6)        // or   t2, t2, t1
      csrrw(x0, x7, 0x180)  // csrw satp, t2
      sfenceVma(x0, x0)     // sfence.vma

      // set mpp to s-mode (1), prepare mret
      raw("    li      t0, ~(3 << 11)") // complex immediate
      csrrs(x6, x0, 0x300)              // csrr t1, mstatus
      and(x6, x6, x5)                   // and  t1, t1, t0
      raw("    li      t2, (1 << 11)")  // complex immediate
      or(x6, x6, x7)                    // or   t1, t1, t2
      csrrw(x0, x6, 0x300)              // csrw mstatus, t1

      csrrw(x0, x1, 0x341) // csrw mepc, ra
      mret()

      align(2)
      label("exit_s_mode")
      ecall()
      jalr(x0, x1, 0) // ret = jalr x0, ra, 0

      align(2)
      label("trap_handler")
      csrrs(x5, x0, 0x341)  // csrr t0, mepc
      csrrs(x6, x0, 0x342)  // csrr t1, mcause
      csrrs(x29, x0, 0x343) // csrr t4, mtval

      // extract exception code
      slli(x30, x6, 1) // slli t5, t1, 1
      srli(x6, x30, 1) // srli t1, t5, 1

      // handle ecall from S-mode
      addi(x28, x0, 9)                            // li t3, 9
      bne(x6, x28, "check_page_fault") // references label
      raw("    li      t2, 3 << 11")              // complex immediate
      csrrs(x0, x7, 0x300)                        // csrs mstatus, t2
      addi(x7, x0, 4)                             // li t2, 4
      j("update_mepc")              // references label

      label("check_page_fault")
      addi(x28, x0, 13)                                 // li t3, 13
      beq(x6, x28, "handle_as_normal_fault") // references label
      addi(x28, x0, 15)                                 // li t3, 15
      beq(x6, x28, "handle_as_normal_fault") // references label
      j("original_logic")                 // references label

      label("handle_as_normal_fault")
      j("original_logic") // references label

      label("original_logic")
      addi(x7, x0, 2)                            // li t2, 2
      addi(x28, x0, 2)                           // li t3, 2
      beq(x6, x28, "illegal_len")     // references label
      addi(x28, x0, 1)                           // li t3, 1
      beq(x6, x28, "instr_fault_len") // references label
      addi(x28, x0, 12)                          // li t3, 12
      beq(x6, x28, "instr_fault_len") // references label

      lhu(x29, x5, 0)                  // lhu t4, 0(t0)
      j("decode_length") // references label

      label("other_len")
      lhu(x29, x5, 0)                  // lhu t4, 0(t0)
      j("decode_length") // references label

      label("illegal_len")
      beqz(x29, "other_len") // pseudo: beq t4, x0, other_len
      j("decode_length") // references label

      label("instr_fault_len")
      addi(x7, x0, 4)                // li t2, 4
      j("update_mepc") // references label

      label("decode_length")
      andi(x29, x29, 3)                         // andi t4, t4, 3
      addi(x28, x0, 3)                          // li t3, 3
      bne(x29, x28, "compressed_len") // references label
      addi(x7, x0, 4)                           // li t2, 4
      j("update_mepc")            // references label

      label("compressed_len")
      addi(x7, x0, 2) // li t2, 2

      label("update_mepc")
      add(x5, x5, x7)      // add  t0, t0, t2
      csrrw(x0, x5, 0x341) // csrw mepc, t0
      csrrw(x0, x0, 0x342) // csrw mcause, x0
      csrrw(x0, x0, 0x343) // csrw mtval, x0
      mret()

      section(".data")
      balign(4096) // balign not yet modeled
      label("pgtbl")
      zero(4096)   // .zero not yet modeled

      section(".tohost", "\"aw\"", "@progbits")
      align(6)
      global("tohost")
      global("fromhost")
      label("tohost")
      dword(0) // .dword not yet modeled
      label("fromhost")
      dword(0)
  Program.emit(outputPath)
