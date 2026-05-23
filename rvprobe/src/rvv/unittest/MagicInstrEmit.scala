// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.unittest

import me.jiuyang.rvprobe.rvv.vtype.Lmul

/** Magic instruction encoder for the upstream pspike co-sim oracle.
 *  pspike scans stage-1 `.S` files for `.word 0x...` instructions of
 *  opcode `0x0B` and uses the embedded fields to read a vector register
 *  group, the active LMUL, and the VXSAT CSR after the test executes.
 *
 *  Bit layout (matches `riscv-vector-tests/generator/insn_g.go:166-182`):
 *  - bits  6:0  = 0b0001011 (opcode 0x0B)
 *  - bits 19:15 = vector_group (rs1 field, 0..31)
 *  - bit  20    = vxsat flag (rs2[0])
 *  - bits 24:21 = LMUL encoding (rs2[4:1])
 *
 *  LMUL encoding follows the spec vlmul[2:0] mapping:
 *  - 0b000 = M1
 *  - 0b001 = M2
 *  - 0b010 = M4
 *  - 0b011 = M8
 *  - 0b101 = Mf8
 *  - 0b110 = Mf4
 *  - 0b111 = Mf2
 *  (0b100 reserved)
 *
 *  Stored in rs2[4:1] (4 bits) — top bit unused in this encoding so it
 *  fits.
 */
object MagicInstrEmit:

  private val Opcode: Int = 0x0B

  /** Encode (vectorGroup, lmul, vxsat) into the 32-bit magic word. */
  def encode(vectorGroup: Int, lmul: Lmul, vxsat: Boolean): Int =
    require(vectorGroup >= 0 && vectorGroup < 32,
      s"vectorGroup must be in [0,31], got $vectorGroup")
    val rs1Field  = (vectorGroup & 0x1f) << 15
    val vxsatBit  = (if vxsat then 1 else 0) << 20
    val lmulBits  = (lmulEncoding(lmul) & 0xf) << 21
    Opcode | rs1Field | vxsatBit | lmulBits

  /** Decode a 32-bit word back to (vectorGroup, lmul, vxsat). The
   *  pspike-decoder regression test (AC-8) uses this to round-trip
   *  every emitted magic word from the rvprobe side.
   */
  def decode(word: Int): Option[(Int, Lmul, Boolean)] =
    if (word & 0x7f) != Opcode then None
    else
      val group = (word >>> 15) & 0x1f
      val vxsat = ((word >>> 20) & 0x1) == 1
      val lEnc  = (word >>> 21) & 0xf
      lmulFromEncoding(lEnc).map(lmul => (group, lmul, vxsat))

  /** Emit a `.word 0x...` assembly directive carrying the magic word.
   *  Use this from `TestSEmit` to terminate each per-iteration block;
   *  pspike's diff pass replaces the placeholder result rows with the
   *  actual computed values.
   */
  def emitAsm(vectorGroup: Int, lmul: Lmul, vxsat: Boolean): String =
    val w = encode(vectorGroup, lmul, vxsat)
    f".word 0x$w%08x"

  /** vlmul[2:0] encoding per RVV spec table. */
  def lmulEncoding(lmul: Lmul): Int = lmul match
    case Lmul.M1  => 0b000
    case Lmul.M2  => 0b001
    case Lmul.M4  => 0b010
    case Lmul.M8  => 0b011
    case Lmul.Mf8 => 0b101
    case Lmul.Mf4 => 0b110
    case Lmul.Mf2 => 0b111

  private def lmulFromEncoding(enc: Int): Option[Lmul] = enc match
    case 0b000 => Some(Lmul.M1)
    case 0b001 => Some(Lmul.M2)
    case 0b010 => Some(Lmul.M4)
    case 0b011 => Some(Lmul.M8)
    case 0b101 => Some(Lmul.Mf8)
    case 0b110 => Some(Lmul.Mf4)
    case 0b111 => Some(Lmul.Mf2)
    case _     => None
