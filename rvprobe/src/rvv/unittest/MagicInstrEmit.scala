// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.unittest

import me.jiuyang.rvprobe.rvv.vtype.Lmul

/** Magic instruction encoder for the upstream pspike co-sim oracle.
 *  pspike scans stage-1 `.S` files for `.word 0x...` instructions of
 *  opcode `0x0B` and uses the embedded fields to read a vector register
 *  group, the active register-group footprint, and the VXSAT CSR after
 *  the test executes.
 *
 *  Bit layout (matches `riscv-vector-tests/generator/insn_g.go:166-182`):
 *  - bits  6:0  = 0b0001011 (opcode 0x0B)
 *  - bits 19:15 = vector_group (rs1 field, 0..31)
 *  - bit  20    = vxsat flag (rs2[0])
 *  - bits 24:21 = `lmul1` (rs2[4:1]) = whole-register count
 *
 *  **`lmul1` semantics**: pspike's `int lmul1 = insn.rs2() >> 1` reads
 *  the number of registers in the group, NOT the architectural
 *  `vlmul[2:0]` encoding. Upstream `gMagicInsn` computes
 *  `lmul1 = max(lmul, 1)` from the combinations table, where `lmul` is
 *  the integer LMUL or `1` for fractional LMULs.
 *
 *  Concrete values stored in rs2[4:1]:
 *  - Lmul.Mf8 / Mf4 / Mf2 -> 1
 *  - Lmul.M1              -> 1
 *  - Lmul.M2              -> 2
 *  - Lmul.M4              -> 4
 *  - Lmul.M8              -> 8
 *
 *  (Round-7 Codex review caught this: the prior version stored the
 *  architectural vlmul encoding here, which made pspike read the
 *  wrong number of registers for M4/M8/fractional cases.)
 */
object MagicInstrEmit:

  private val Opcode: Int = 0x0B

  /** Encode (vectorGroup, lmul, vxsat) into the 32-bit magic word. */
  def encode(vectorGroup: Int, lmul: Lmul, vxsat: Boolean): Int =
    require(vectorGroup >= 0 && vectorGroup < 32,
      s"vectorGroup must be in [0,31], got $vectorGroup")
    val rs1Field  = (vectorGroup & 0x1f) << 15
    val vxsatBit  = (if vxsat then 1 else 0) << 20
    val lmulBits  = (wholeRegisterCount(lmul) & 0xf) << 21
    Opcode | rs1Field | vxsatBit | lmulBits

  /** Decode a 32-bit word back to (vectorGroup, wholeRegCount, vxsat).
   *  Note: decode returns the whole-register count (1, 2, 4, or 8)
   *  rather than the Lmul case, because fractional LMULs all collapse
   *  to 1 in the magic word and cannot be distinguished after decode.
   */
  def decode(word: Int): Option[(Int, Int, Boolean)] =
    if (word & 0x7f) != Opcode then None
    else
      val group   = (word >>> 15) & 0x1f
      val vxsat   = ((word >>> 20) & 0x1) == 1
      val wholeRC = (word >>> 21) & 0xf
      if Set(1, 2, 4, 8).contains(wholeRC) then Some((group, wholeRC, vxsat))
      else None

  /** Emit a `.word 0x...` assembly directive carrying the magic word.
   *  Use this from `TestSEmit` to terminate each per-iteration block;
   *  pspike's diff pass replaces the placeholder result rows with the
   *  actual computed values.
   */
  def emitAsm(vectorGroup: Int, lmul: Lmul, vxsat: Boolean): String =
    val w = encode(vectorGroup, lmul, vxsat)
    f".word 0x$w%08x"

  /** Per the upstream gMagicInsn contract, the rs2[4:1] field carries
   *  the whole-register count (1, 2, 4, or 8) — `max(lmul, 1)` where
   *  fractional LMULs collapse to 1.
   */
  def wholeRegisterCount(lmul: Lmul): Int = lmul match
    case Lmul.Mf8 | Lmul.Mf4 | Lmul.Mf2 => 1
    case Lmul.M1                        => 1
    case Lmul.M2                        => 2
    case Lmul.M4                        => 4
    case Lmul.M8                        => 8
