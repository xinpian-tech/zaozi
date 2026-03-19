// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.privilege

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** CSR address constants. */
object CSR:
  val MSTATUS    = 0x300
  val MEDELEG    = 0x302
  val MIDELEG    = 0x303
  val MTVEC      = 0x305
  val MCOUNTEREN = 0x306
  val MSCRATCH   = 0x340
  val MEPC       = 0x341
  val MCAUSE     = 0x342
  val MTVAL      = 0x343
  val PMPCFG0    = 0x3a0
  val PMPADDR0   = 0x3b0
  val PMPADDR1   = 0x3b1
  val PMPADDR2   = 0x3b2
  val PMPADDR3   = 0x3b3
  val SATP       = 0x180
  val SSTATUS    = 0x100
  val STVEC      = 0x105
  val SEPC       = 0x141
  val SCAUSE     = 0x142
  val STVAL      = 0x143

/** Exception cause constants. */
object Cause:
  val INSN_ACCESS_FAULT  = 1
  val ILLEGAL_INSN       = 2
  val LOAD_ACCESS_FAULT  = 5
  val STORE_ACCESS_FAULT = 7
  val ECALL_FROM_S       = 9
  val INSN_PAGE_FAULT    = 12
  val LOAD_PAGE_FAULT    = 13
  val STORE_PAGE_FAULT   = 15

/** Reusable assembly snippets for bare-metal privilege verification probes.
  *
  * Register convention (matches Program.scala): x5=mepc/scratch, x6=mcause/scratch, x7=advance, x28-x30=scratch.
  */
object PrivilegeProbeLib:

  /** Emit `.text` + `_start` + install trap handler to mtvec. */
  def textStartWithTrap(
    trapLabel: String = "trap_handler"
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    section(".text")
    global("_start")
    label("_start")
    la(x5, trapLabel)
    csrrw(x0, x5, CSR.MTVEC)

  /** PMP: NAPOT covering entire address space (pmpaddr0=-1, pmpcfg0=0x1f). */
  def pmpOpenAll(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    addi(x5, x0, -1)
    csrrw(x0, x5, CSR.PMPADDR0)
    addi(x5, x0, 0x1f)
    csrrw(x0, x5, CSR.PMPCFG0)

  /** Configure a single PMP entry by writing pmpaddr and the corresponding byte in pmpcfg.
    *
    * @param entry
    *   PMP entry number (0-3)
    * @param addr
    *   value to write to pmpaddr{entry}
    * @param cfg
    *   8-bit configuration byte for this entry
    */
  def pmpConfigure(
    entry: Int,
    addr:  Long,
    cfg:   Int
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    val pmpaddrCsr = CSR.PMPADDR0 + entry
    li(x5, addr)
    csrrw(x0, x5, pmpaddrCsr)
    // Read current pmpcfg0, clear the byte for this entry, OR in the new cfg
    csrrs(x6, x0, CSR.PMPCFG0)
    li(x5, ~(0xffL << (entry * 8)))
    and(x6, x6, x5)
    li(x5, cfg.toLong << (entry * 8))
    or(x6, x6, x5)
    csrrw(x0, x6, CSR.PMPCFG0)

  /** Switch to S-mode: set mpp=01, write mepc=targetLabel, mret. */
  def switchToSMode(
    targetLabel: String
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    li(x5, ~(3L << 11))
    csrrs(x6, x0, CSR.MSTATUS)
    and(x6, x6, x5)
    li(x7, 1L << 11)
    or(x6, x6, x7)
    csrrw(x0, x6, CSR.MSTATUS)
    la(x5, targetLabel)
    csrrw(x0, x5, CSR.MEPC)
    mret()

  /** Enable Sv39 paging: load page table base, set satp mode=8, sfence.vma.
    *
    * @param pgtblLabel
    *   label of the page table root in .data
    */
  def enableSv39(
    pgtblLabel: String = "pgtbl"
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    la(x5, pgtblLabel)
    addi(x7, x0, 8)
    slli(x7, x7, 60)
    srli(x6, x5, 12)
    or(x7, x7, x6)
    csrrw(x0, x7, CSR.SATP)
    sfenceVma(x0, x0)

  /** Generic M-mode trap handler (extracted from Program.scala).
    *
    * Reads mepc/mcause/mtval. For ecall-from-S, restores M-mode (sets mpp=11) and advances mepc by 4. For instruction
    * access/page faults, advances mepc by 4. For other exceptions, decodes instruction length and advances mepc
    * accordingly.
    */
  def trapHandler(
    handlerLabel: String = "trap_handler"
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    align(2)
    label(handlerLabel)
    csrrs(x5, x0, CSR.MEPC)
    csrrs(x6, x0, CSR.MCAUSE)
    csrrs(x29, x0, CSR.MTVAL)

    // extract exception code (clear interrupt bit)
    slli(x30, x6, 1)
    srli(x6, x30, 1)

    // handle ecall from S-mode
    addi(x28, x0, Cause.ECALL_FROM_S)
    bne(x6, x28, "check_page_fault")
    li(x7, 3L << 11)
    csrrs(x0, x7, CSR.MSTATUS)
    addi(x7, x0, 4)
    j("update_mepc")

    label("check_page_fault")
    addi(x28, x0, Cause.LOAD_PAGE_FAULT)
    beq(x6, x28, "handle_as_normal_fault")
    addi(x28, x0, Cause.STORE_PAGE_FAULT)
    beq(x6, x28, "handle_as_normal_fault")
    j("original_logic")

    label("handle_as_normal_fault")
    j("original_logic")

    label("original_logic")
    addi(x7, x0, 2)
    addi(x28, x0, Cause.ILLEGAL_INSN)
    beq(x6, x28, "illegal_len")
    addi(x28, x0, Cause.INSN_ACCESS_FAULT)
    beq(x6, x28, "instr_fault_len")
    addi(x28, x0, Cause.INSN_PAGE_FAULT)
    beq(x6, x28, "instr_fault_len")

    lhu(x29, x5, 0)
    j("decode_length")

    label("other_len")
    lhu(x29, x5, 0)
    j("decode_length")

    label("illegal_len")
    beqz(x29, "other_len")
    j("decode_length")

    label("instr_fault_len")
    addi(x7, x0, 4)
    j("update_mepc")

    label("decode_length")
    andi(x29, x29, 3)
    addi(x28, x0, 3)
    bne(x29, x28, "compressed_len")
    addi(x7, x0, 4)
    j("update_mepc")

    label("compressed_len")
    addi(x7, x0, 2)

    label("update_mepc")
    add(x5, x5, x7)
    csrrw(x0, x5, CSR.MEPC)
    csrrw(x0, x0, CSR.MCAUSE)
    csrrw(x0, x0, CSR.MTVAL)
    mret()

  /** Trap handler that additionally records mcause to a memory location.
    *
    * Same as [[trapHandler]] but also stores the exception cause to `resultLabel`. Uses unique label suffixes to avoid
    * conflicts with [[trapHandler]].
    */
  def trapHandlerWithRecord(
    handlerLabel: String = "trap_handler_rec",
    resultLabel:  String = "trap_cause"
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    align(2)
    label(handlerLabel)
    csrrs(x5, x0, CSR.MEPC)
    csrrs(x6, x0, CSR.MCAUSE)
    csrrs(x29, x0, CSR.MTVAL)

    // record mcause to memory
    la(x28, resultLabel)
    sd(x28, x6, 0)

    // extract exception code
    slli(x30, x6, 1)
    srli(x6, x30, 1)

    // handle ecall from S-mode
    addi(x28, x0, Cause.ECALL_FROM_S)
    bne(x6, x28, "rec_check_page_fault")
    li(x7, 3L << 11)
    csrrs(x0, x7, CSR.MSTATUS)
    addi(x7, x0, 4)
    j("rec_update_mepc")

    label("rec_check_page_fault")
    addi(x28, x0, Cause.LOAD_PAGE_FAULT)
    beq(x6, x28, "rec_handle_fault")
    addi(x28, x0, Cause.STORE_PAGE_FAULT)
    beq(x6, x28, "rec_handle_fault")
    addi(x28, x0, Cause.LOAD_ACCESS_FAULT)
    beq(x6, x28, "rec_handle_fault")
    addi(x28, x0, Cause.STORE_ACCESS_FAULT)
    beq(x6, x28, "rec_handle_fault")
    addi(x28, x0, Cause.INSN_ACCESS_FAULT)
    beq(x6, x28, "rec_instr_fault_len")
    addi(x28, x0, Cause.INSN_PAGE_FAULT)
    beq(x6, x28, "rec_instr_fault_len")
    j("rec_original_logic")

    label("rec_handle_fault")
    j("rec_original_logic")

    label("rec_original_logic")
    addi(x7, x0, 2)
    addi(x28, x0, Cause.ILLEGAL_INSN)
    beq(x6, x28, "rec_illegal_len")

    lhu(x29, x5, 0)
    j("rec_decode_length")

    label("rec_other_len")
    lhu(x29, x5, 0)
    j("rec_decode_length")

    label("rec_illegal_len")
    beqz(x29, "rec_other_len")
    j("rec_decode_length")

    label("rec_instr_fault_len")
    addi(x7, x0, 4)
    j("rec_update_mepc")

    label("rec_decode_length")
    andi(x29, x29, 3)
    addi(x28, x0, 3)
    bne(x29, x28, "rec_compressed_len")
    addi(x7, x0, 4)
    j("rec_update_mepc")

    label("rec_compressed_len")
    addi(x7, x0, 2)

    label("rec_update_mepc")
    add(x5, x5, x7)
    csrrw(x0, x5, CSR.MEPC)
    csrrw(x0, x0, CSR.MCAUSE)
    csrrw(x0, x0, CSR.MTVAL)
    mret()

  /** Emit the exit sequence: write 1 to `tohost` then spin forever. */
  def exitSeq(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    label("exit")
    addi(x5, x0, 1)
    la(x6, "tohost")
    sd(x6, x5, 0)
    label("spin")
    j("spin")

  /** Emit the `.tohost` section with `tohost` and `fromhost` symbols. */
  def tohostSection(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    section(".tohost", "aw", "@progbits")
    align(6)
    global("tohost")
    global("fromhost")
    label("tohost")
    dword(0)
    label("fromhost")
    dword(0)

  /** Emit a `.data` section with a page-aligned page table buffer.
    *
    * @param pgtblLabel
    *   label name
    * @param size
    *   number of bytes to reserve
    */
  def pageTableData(
    pgtblLabel: String = "pgtbl",
    size:       Int = 4096
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    section(".data")
    balign(4096)
    label(pgtblLabel)
    zero(size)

  /** Emit a `.data` section with a trap result dword. */
  def trapResultData(
    resultLabel: String = "trap_cause"
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    section(".data")
    balign(8)
    label(resultLabel)
    dword(0)
