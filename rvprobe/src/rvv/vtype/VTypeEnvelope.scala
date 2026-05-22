// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.vtype

final case class VTypeEnvelope private (vtype: VType, vl: Int, vlen: Int, xlen: Int):
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

object VTypeEnvelope:
  def apply(vtype: VType, vl: Int, vlen: Int, xlen: Int): Either[String, VTypeEnvelope] =
    if !VType.isLegal(vtype.sew, vtype.lmul, xlen) then
      Left(s"illegal VType: SEW=${vtype.sewBits} LMUL=${vtype.lmulNumerator}/${vtype.lmulDenominator} ELEN=$xlen")
    else if vlen <= 0 || (vlen & (vlen - 1)) != 0 then
      Left(s"VLEN must be a positive power of two, got $vlen")
    else if xlen != 32 && xlen != 64 then
      Left(s"XLEN must be 32 or 64, got $xlen")
    else
      val ev      = new VTypeEnvelope(vtype, vl, vlen, xlen)
      val maxVl   = ev.maxElements
      if vl < 0 || vl > maxVl then
        Left(s"vl=$vl exceeds maxElements=$maxVl for VLEN=$vlen SEW=${vtype.sewBits} LMUL=${vtype.lmulNumerator}/${vtype.lmulDenominator}")
      else Right(ev)

  def unsafe(vtype: VType, vl: Int, vlen: Int, xlen: Int): VTypeEnvelope =
    apply(vtype, vl, vlen, xlen) match
      case Right(e) => e
      case Left(m)  => throw new IllegalArgumentException(m)
