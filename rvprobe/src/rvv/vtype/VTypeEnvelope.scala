// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.vtype

final class VTypeEnvelope private (val vtype: VType, val vl: Int, val vlen: Int, val xlen: Int):
  def elen: Int = xlen

  def elementsPerRegister: Int = vlen / vtype.sewBits

  def effectiveLmulVlenBits: Int =
    vlen * vtype.lmulNumerator / vtype.lmulDenominator

  def maxElements: Int = effectiveLmulVlenBits / vtype.sewBits

  def registerGroupSize: Int =
    val lmul = vtype.lmul
    if lmul.denominator > 1 then 1
    else lmul.numerator

  def vill: Boolean = false

  override def equals(other: Any): Boolean = other match
    case that: VTypeEnvelope =>
      vtype == that.vtype && vl == that.vl && vlen == that.vlen && xlen == that.xlen
    case _                   => false

  override def hashCode: Int =
    var h = 1
    h = 31 * h + vtype.hashCode
    h = 31 * h + vl.hashCode
    h = 31 * h + vlen.hashCode
    h = 31 * h + xlen.hashCode
    h

  override def toString: String =
    s"VTypeEnvelope(sew=${vtype.sewBits}, lmul=${vtype.lmulNumerator}/${vtype.lmulDenominator}, " +
      s"vta=${vtype.vta}, vma=${vtype.vma}, vl=$vl, vlen=$vlen, xlen=$xlen, vill=false)"

object VTypeEnvelope:
  def apply(vtype: VType, vl: Int, vlen: Int, xlen: Int): Either[String, VTypeEnvelope] =
    if xlen != 32 && xlen != 64 then
      Left(s"XLEN must be 32 or 64, got $xlen")
    else if vlen <= 0 || (vlen & (vlen - 1)) != 0 then
      Left(s"VLEN must be a positive power of two, got $vlen")
    else if !VType.isLegal(vtype.sew, vtype.lmul, xlen) then
      Left(s"illegal VType: SEW=${vtype.sewBits} LMUL=${vtype.lmulNumerator}/${vtype.lmulDenominator} ELEN=$xlen")
    else
      val ev    = new VTypeEnvelope(vtype, vl, vlen, xlen)
      val maxVl = ev.maxElements
      if vl < 0 || vl > maxVl then
        Left(
          s"vl=$vl exceeds maxElements=$maxVl for VLEN=$vlen SEW=${vtype.sewBits} " +
            s"LMUL=${vtype.lmulNumerator}/${vtype.lmulDenominator}")
      else Right(ev)

  def unsafe(vtype: VType, vl: Int, vlen: Int, xlen: Int): VTypeEnvelope =
    apply(vtype, vl, vlen, xlen) match
      case Right(e) => e
      case Left(m)  => throw new IllegalArgumentException(m)
