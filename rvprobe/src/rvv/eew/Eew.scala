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

final case class WidthScale(numerator: Int, denominator: Int):
  require(numerator > 0 && denominator > 0, s"WidthScale: invalid $numerator/$denominator")

  def reduced: WidthScale =
    val g = WidthScale.gcd(numerator, denominator)
    WidthScale(numerator / g, denominator / g)

  def `*`(sewBits: Int): Option[Int] =
    val raw = sewBits * numerator
    if raw % denominator != 0 then None else Some(raw / denominator)

object WidthScale:
  val One:     WidthScale = WidthScale(1, 1)
  val By2:     WidthScale = WidthScale(2, 1)
  val By4:     WidthScale = WidthScale(4, 1)
  val By8:     WidthScale = WidthScale(8, 1)
  val Narrow2: WidthScale = WidthScale(1, 2)
  val Narrow4: WidthScale = WidthScale(1, 4)

  def gcd(a: Int, b: Int): Int = if b == 0 then a else gcd(b, a % b)

final case class OperandWidthProfile(
  scales:   Map[OperandRole, WidthScale] = Map.empty,
  maskDest: Boolean                      = false
):
  def scaleOf(role: OperandRole): WidthScale = scales.getOrElse(role, WidthScale.One)

object OperandWidthProfile:
  val default: OperandWidthProfile = OperandWidthProfile()

  def maskDestination(): OperandWidthProfile = OperandWidthProfile(maskDest = true)

enum OverlapRule:
  case None
  case DestNoVs1Overlap
  case DestNoVs2Overlap
  case DestNoMaskOverlap
  case WideningDestSourceOverlap

private val LegalSew: Set[Int] = Set(8, 16, 32, 64)

final case class EmulRatio(numerator: Int, denominator: Int):
  require(numerator > 0 && denominator > 0, s"EmulRatio: invalid $numerator/$denominator")

  def asWholeRegisters: Int = if denominator == 1 then numerator else 1

  def isFractional: Boolean = denominator > 1

  /** Spec-legal EMUL: 1/8, 1/4, 1/2, 1, 2, 4, 8. */
  def isWithinSpec: Boolean =
    val r = reduced
    if r.denominator == 1 then Set(1, 2, 4, 8).contains(r.numerator)
    else r.numerator == 1 && Set(2, 4, 8).contains(r.denominator)

  def reduced: EmulRatio =
    val g = EmulRatio.gcd(numerator, denominator)
    EmulRatio(numerator / g, denominator / g)

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
    profile:    OperandWidthProfile = OperandWidthProfile.default,
    indexedEew: Option[Int]         = None
  ): Either[String, Int] =
    OperandClass.of(role, schema) match
      case OperandClass.VectorElementSew                          =>
        if profile.maskDest && schema.destRole.contains(role) then Right(1)
        else
          val scale = profile.scaleOf(role)
          val sew   = env.vtype.sewBits
          (scale * sew) match
            case None      =>
              Left(
                s"non-integral EEW for role=$role schema=$schema sew=$sew scale=${scale.numerator}/${scale.denominator}")
            case Some(eew) =>
              if eew > env.xlen then
                Left(s"EEW=$eew exceeds XLEN=${env.xlen} for role=$role schema=$schema")
              else if !LegalSew.contains(eew) then
                Left(s"EEW=$eew not in legal {8,16,32,64} for role=$role schema=$schema")
              else Right(eew)
      case OperandClass.VectorElementIndex                        =>
        indexedEew match
          case None      => Left(s"indexed schema $schema role $role requires indexedEew")
          case Some(eew) =>
            if !LegalSew.contains(eew) then Left(s"indexed EEW=$eew not in legal {8,16,32,64}")
            else if eew > env.xlen then Left(s"indexed EEW=$eew exceeds XLEN=${env.xlen}")
            else Right(eew)
      case OperandClass.Mask                                      => Right(1)
      case OperandClass.ScalarInteger | OperandClass.ScalarMemory => Right(env.xlen)
      case OperandClass.ScalarFp                                  => Right(env.vtype.sewBits)
      case OperandClass.Immediate | OperandClass.GroupedMask      => Right(0)

  def unsafe(
    role:       OperandRole,
    schema:     Schema,
    env:        VTypeEnvelope,
    profile:    OperandWidthProfile = OperandWidthProfile.default,
    indexedEew: Option[Int]         = None
  ): Int =
    compute(role, schema, env, profile, indexedEew) match
      case Right(v) => v
      case Left(m)  => throw new IllegalArgumentException(m)

object Emul:
  def compute(
    role:       OperandRole,
    schema:     Schema,
    env:        VTypeEnvelope,
    profile:    OperandWidthProfile = OperandWidthProfile.default,
    indexedEew: Option[Int]         = None
  ): Either[String, EmulRatio] =
    val cls = OperandClass.of(role, schema)
    cls match
      case OperandClass.VectorElementSew | OperandClass.VectorElementIndex =>
        if profile.maskDest && schema.destRole.contains(role) then Right(EmulRatio(1, 1))
        else
          Eew.compute(role, schema, env, profile, indexedEew).flatMap { eew =>
            val sew   = env.vtype.sewBits
            val lmulN = env.vtype.lmulNumerator
            val lmulD = env.vtype.lmulDenominator
            val emul  = EmulRatio.reduced(eew * lmulN, sew * lmulD)
            if emul.isWithinSpec then Right(emul)
            else
              Left(
                s"EMUL=${emul.numerator}/${emul.denominator} outside spec [1/8,8] for role=$role schema=$schema")
          }
      case _                                                               =>
        Right(EmulRatio(1, 1))

  def unsafe(
    role:       OperandRole,
    schema:     Schema,
    env:        VTypeEnvelope,
    profile:    OperandWidthProfile = OperandWidthProfile.default,
    indexedEew: Option[Int]         = None
  ): EmulRatio =
    compute(role, schema, env, profile, indexedEew) match
      case Right(v) => v
      case Left(m)  => throw new IllegalArgumentException(m)

object RegisterFootprint:
  def of(
    role:       OperandRole,
    schema:     Schema,
    env:        VTypeEnvelope,
    profile:    OperandWidthProfile = OperandWidthProfile.default,
    indexedEew: Option[Int]         = None,
    nfields:    Int                 = 1
  ): Either[String, Int] =
    if nfields < 1 || nfields > 8 then Left(s"NFIELDS must be in [1,8], got $nfields")
    else
      OperandClass.of(role, schema) match
        case OperandClass.VectorElementSew                                   =>
          if profile.maskDest && schema.destRole.contains(role) then Right(1)
          else
            Emul.compute(role, schema, env, profile, indexedEew).map { emul =>
              emul.asWholeRegisters * nfields
            }
        case OperandClass.VectorElementIndex                                 =>
          Emul.compute(role, schema, env, profile, indexedEew).map(_.asWholeRegisters)
        case OperandClass.Mask | OperandClass.GroupedMask                    => Right(1)
        case _                                                               => Right(0)

  def unsafe(
    role:       OperandRole,
    schema:     Schema,
    env:        VTypeEnvelope,
    profile:    OperandWidthProfile = OperandWidthProfile.default,
    indexedEew: Option[Int]         = None,
    nfields:    Int                 = 1
  ): Int =
    of(role, schema, env, profile, indexedEew, nfields) match
      case Right(v) => v
      case Left(m)  => throw new IllegalArgumentException(m)

object NfieldsValidator:
  def check(env: VTypeEnvelope, nfields: Int): Either[String, Unit] =
    if nfields < 1 || nfields > 8 then Left(s"NFIELDS must be in [1,8], got $nfields")
    else
      val lmulWhole =
        if env.vtype.lmul.denominator == 1 then env.vtype.lmul.numerator else 1
      if nfields * lmulWhole > 8 then
        Left(s"NFIELDS × EMUL > 8: nfields=$nfields lmul=$lmulWhole")
      else Right(())
