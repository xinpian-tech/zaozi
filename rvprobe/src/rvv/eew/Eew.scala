// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.eew

import me.jiuyang.rvprobe.rvv.{OperandRole, Schema}
import me.jiuyang.rvprobe.rvv.vtype.VTypeEnvelope

enum OperandClass:
  case VectorElementSew
  case VectorElementIndex
  case Mask
  case ScalarInteger
  case ScalarFp
  case ScalarMemory
  case Immediate
  case GroupedMask

object OperandClass:
  def of(role: OperandRole, schema: Schema): OperandClass =
    val isIndexedSlot = schema.indexedSlot.contains(role)
    role match
      case OperandRole.Vs2 if isIndexedSlot => VectorElementIndex
      case OperandRole.Vd                   => VectorElementSew
      case OperandRole.Vs1                  => VectorElementSew
      case OperandRole.Vs2                  => VectorElementSew
      case OperandRole.Vs3                  => VectorElementSew
      case OperandRole.Vm | OperandRole.V0  => Mask
      case OperandRole.Rs1                  => ScalarInteger
      case OperandRole.Rs1Mem               => ScalarMemory
      case OperandRole.Rs2                  => ScalarInteger
      case OperandRole.Rd                   => ScalarInteger
      case OperandRole.Fs1                  => ScalarFp
      case OperandRole.Fd                   => ScalarFp
      case OperandRole.Imm                  => Immediate
      case OperandRole.Uimm                 => Immediate
      case OperandRole.VmGroup2             => GroupedMask
      case OperandRole.VmGroup3             => GroupedMask

enum Widening:
  case None
  case By2
  case By4
  case Narrow2
  case Narrow4

object Widening:
  def factorNumerator(w: Widening):   Int = w match
    case None    => 1
    case By2     => 2
    case By4     => 4
    case Narrow2 => 1
    case Narrow4 => 1
  def factorDenominator(w: Widening): Int = w match
    case None    => 1
    case By2     => 1
    case By4     => 1
    case Narrow2 => 2
    case Narrow4 => 4

enum OverlapRule:
  case None
  case DestNoVs1Overlap
  case DestNoVs2Overlap
  case DestNoMaskOverlap
  case WideningDestSourceOverlap

final case class EmulRatio(numerator: Int, denominator: Int):
  def asWholeRegisters: Int = if denominator == 1 then numerator else 1

  def isFractional: Boolean = denominator > 1

object EmulRatio:
  def reduced(numerator: Int, denominator: Int): EmulRatio =
    require(numerator > 0 && denominator > 0, s"EmulRatio: invalid $numerator/$denominator")
    val g = gcd(numerator, denominator)
    EmulRatio(numerator / g, denominator / g)

  private def gcd(a: Int, b: Int): Int = if b == 0 then a else gcd(b, a % b)

object Eew:
  def compute(
    role:       OperandRole,
    schema:     Schema,
    env:        VTypeEnvelope,
    widening:   Widening    = Widening.None,
    indexedEew: Option[Int] = None
  ): Int =
    OperandClass.of(role, schema) match
      case OperandClass.VectorElementSew                       =>
        val sew = env.vtype.sewBits
        val n   = Widening.factorNumerator(widening)
        val d   = Widening.factorDenominator(widening)
        sew * n / d
      case OperandClass.VectorElementIndex                     =>
        indexedEew.getOrElse(
          throw new IllegalArgumentException(s"indexed schema ${schema} role ${role} requires indexedEew"))
      case OperandClass.Mask                                   => 1
      case OperandClass.ScalarInteger | OperandClass.ScalarMemory => env.xlen
      case OperandClass.ScalarFp                               => env.vtype.sewBits
      case OperandClass.Immediate | OperandClass.GroupedMask   => 0

object Emul:
  def compute(
    role:       OperandRole,
    schema:     Schema,
    env:        VTypeEnvelope,
    widening:   Widening    = Widening.None,
    indexedEew: Option[Int] = None
  ): EmulRatio =
    val cls = OperandClass.of(role, schema)
    cls match
      case OperandClass.VectorElementSew | OperandClass.VectorElementIndex =>
        val eew     = Eew.compute(role, schema, env, widening, indexedEew)
        val sew     = env.vtype.sewBits
        val lmulN   = env.vtype.lmulNumerator
        val lmulD   = env.vtype.lmulDenominator
        EmulRatio.reduced(eew * lmulN, sew * lmulD)
      case _                                                               =>
        EmulRatio(1, 1)

object RegisterFootprint:
  def of(
    role:       OperandRole,
    schema:     Schema,
    env:        VTypeEnvelope,
    widening:   Widening    = Widening.None,
    indexedEew: Option[Int] = None,
    nfields:    Int         = 1
  ): Int =
    OperandClass.of(role, schema) match
      case OperandClass.VectorElementSew | OperandClass.VectorElementIndex =>
        val emul     = Emul.compute(role, schema, env, widening, indexedEew)
        val perField = emul.asWholeRegisters
        perField * nfields
      case OperandClass.Mask | OperandClass.GroupedMask                    => 1
      case _                                                               => 0

object NfieldsValidator:
  def check(env: VTypeEnvelope, nfields: Int): Either[String, Unit] =
    if nfields < 1 || nfields > 8 then Left(s"NFIELDS must be in [1,8], got $nfields")
    else
      val lmulWhole =
        if env.vtype.lmul.denominator == 1 then env.vtype.lmul.numerator
        else 1
      if nfields * lmulWhole > 8 then
        Left(s"NFIELDS × EMUL > 8: nfields=$nfields lmul=$lmulWhole")
      else Right(())
