// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.vtype

enum Sew(val bits: Int):
  case Sew8  extends Sew(8)
  case Sew16 extends Sew(16)
  case Sew32 extends Sew(32)
  case Sew64 extends Sew(64)

object Sew:
  val all: List[Sew] = List(Sew8, Sew16, Sew32, Sew64)

enum Lmul(val numerator: Int, val denominator: Int):
  case Mf8 extends Lmul(1, 8)
  case Mf4 extends Lmul(1, 4)
  case Mf2 extends Lmul(1, 2)
  case M1  extends Lmul(1, 1)
  case M2  extends Lmul(2, 1)
  case M4  extends Lmul(4, 1)
  case M8  extends Lmul(8, 1)

object Lmul:
  val all:        List[Lmul] = List(Mf8, Mf4, Mf2, M1, M2, M4, M8)
  val fractional: List[Lmul] = List(Mf8, Mf4, Mf2)
  val integer:    List[Lmul] = List(M1, M2, M4, M8)

enum Vta:
  case Agnostic
  case Undisturbed

enum Vma:
  case Agnostic
  case Undisturbed

final case class VType(sew: Sew, lmul: Lmul, vta: Vta, vma: Vma):
  def sewBits:        Int = sew.bits
  def lmulNumerator:  Int = lmul.numerator
  def lmulDenominator: Int = lmul.denominator
  def isFractional:   Boolean = lmul.denominator > 1

object VType:
  def isLegal(sew: Sew, lmul: Lmul, elen: Int): Boolean =
    if sew.bits > elen then false
    else lmul match
      case Lmul.Mf8 => sew.bits * 8 <= elen
      case Lmul.Mf4 => sew.bits * 4 <= elen
      case Lmul.Mf2 => sew.bits * 2 <= elen
      case _        => true
