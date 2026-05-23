// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.unittest

import me.jiuyang.rvprobe.rvv.eew.{Emul, OperandWidthProfile}
import me.jiuyang.rvprobe.rvv.pred.{TuplePred, ValuePred}
import me.jiuyang.rvprobe.rvv.vtype.{Sew, VTypeEnvelope}
import me.jiuyang.rvprobe.rvv.{OperandRole, Schema}

import scala.util.Random

/** Deterministic predicate-to-vector-data lowering. Per the design
 *  contract (Codex round-1 DISAGREE resolution), this lives OUTSIDE
 *  the SMT solver: pure function, no Solver calls, no SMT model
 *  variables, no retry loop. The solver's stage-1 (opcode) + stage-2
 *  (scalar args) contract is unchanged.
 *
 *  Inputs:
 *  - operand role (Vd / Vs1 / Vs2 / ...)
 *  - schema (one of the 39 sealed family entries)
 *  - VTypeEnvelope (SEW, LMUL, VLEN, XLEN)
 *  - predicate set (per-operand TuplePred selection + per-element
 *    ValuePred selection)
 *  - optional deterministic seed
 *
 *  Output: `Vector[BigInt]` with element count `envelope.maxElements`,
 *  each element sized to the operand's EEW. Caller embeds these into
 *  the `.S` data section.
 */
object ElemValueLowering:

  /** Lower a single `ValuePred` to a concrete element value at the
   *  given SEW. Random / Lit are seeded deterministically; named
   *  predicates produce their canonical bit pattern.
   */
  def lowerValue(p: ValuePred, sew: Sew, seed: Long = 0L): BigInt =
    val width = sew.bits
    val mask  = (BigInt(1) << width) - 1
    p match
      case ValuePred.Zero                     => BigInt(0)
      case ValuePred.One                      => BigInt(1)
      case ValuePred.MinusOne                 => mask
      case ValuePred.AllOnes(_)               => mask
      case ValuePred.MaxSigned(_)             => (BigInt(1) << (width - 1)) - 1
      case ValuePred.MinSigned(_)             => BigInt(1) << (width - 1)
      case ValuePred.MaxUnsigned(_)           => mask
      case ValuePred.SignBitOnly(_)           => BigInt(1) << (width - 1)
      case ValuePred.NearMaxSigned(_, offset) =>
        ((BigInt(1) << (width - 1)) - 1 - BigInt(offset)) & mask
      case ValuePred.SmallSigned(v)           =>
        if v >= 0 then BigInt(v) & mask
        else (BigInt(v) + (BigInt(1) << width)) & mask
      case ValuePred.BitPattern(value, _)     => value & mask
      case ValuePred.Lit(value, _)            => value & mask
      case ValuePred.Random(_, s)             =>
        val rng = new Random(s ^ seed ^ width.toLong)
        var v   = BigInt(0)
        // Generate width bits deterministically.
        var bitsLeft = width
        while bitsLeft > 0 do
          val take = bitsLeft min 32
          v        = (v << take) | (BigInt(rng.nextInt() & ((1 << take) - 1)))
          bitsLeft -= take
        v & mask

  /** Lower a single `TuplePred` to one or more row instances. Each
   *  row is a list of element values; the caller decides how to embed
   *  them into a vector. For most tuple predicates a single row
   *  suffices; for predicates like `Random` a configurable count can
   *  be requested.
   */
  def lowerTuple(p: TuplePred, sew: Sew, arity: Int, seed: Long = 0L): List[BigInt] =
    val width = sew.bits
    val mask  = (BigInt(1) << width) - 1
    val ms    = (BigInt(1) << (width - 1)) - 1
    val mn    = BigInt(1) << (width - 1)
    p match
      case TuplePred.AllZero                          => List.fill(arity)(BigInt(0))
      case TuplePred.AllAllOnes(_)                    => List.fill(arity)(mask)
      case TuplePred.AllSame(v, _)                    => List.fill(arity)(v & mask)
      case TuplePred.ZeroPlusSmall(other)             =>
        val small = if other >= 0 then BigInt(other) & mask
                    else (BigInt(other) + (BigInt(1) << width)) & mask
        List(BigInt(0), small).padTo(arity, BigInt(0))
      case TuplePred.MaxPlusOne(_)                    => List(ms, BigInt(1)).padTo(arity, BigInt(0))
      case TuplePred.MaxPlusSmallPositive(_, other)   =>
        List(ms, BigInt(other) & mask).padTo(arity, BigInt(0))
      case TuplePred.AllOnesPlusAllOnes(_)            => List.fill(arity)(mask)
      case TuplePred.NegSmallPlusPosSmall(neg, pos)   =>
        val negV = (BigInt(neg) + (BigInt(1) << width)) & mask
        List(negV, BigInt(pos) & mask).padTo(arity, BigInt(0))
      case TuplePred.ShiftByZero                      => List(BigInt(0), mask).padTo(arity, mask)
      case TuplePred.ShiftByOne                       => List(BigInt(1), mask).padTo(arity, mask)
      case TuplePred.ShiftBySewMinus1(_)              =>
        List(BigInt(width - 1), mask).padTo(arity, mask)
      case TuplePred.ShiftBySewOrAbove(_)             =>
        List(BigInt(width), mask).padTo(arity, mask)
      case TuplePred.DivByZero(_)                     => List(BigInt(1), BigInt(0)).padTo(arity, BigInt(0))
      case TuplePred.MinSignedDivZero(_)              => List(mn, BigInt(0)).padTo(arity, BigInt(0))
      case TuplePred.ZeroDivAnything(_)               => List(BigInt(0), BigInt(1)).padTo(arity, BigInt(1))
      case TuplePred.BitPatternPair(_)                =>
        List(BigInt("6db6db6db6db6db7", 16) & mask, BigInt(1)).padTo(arity, BigInt(0))
      case TuplePred.Lit(rows, _)                     =>
        rows.map(_ & mask).padTo(arity, BigInt(0))

  /** Build a witness vector of the given element count, drawing one
   *  element per predicate (round-robin) until filled. Deterministic
   *  given `seed`.
   */
  def buildVector(
    preds:        Seq[ValuePred],
    sew:          Sew,
    elementCount: Int,
    seed:         Long = 0L
  ): Vector[BigInt] =
    require(elementCount > 0, s"elementCount must be > 0, got $elementCount")
    if preds.isEmpty then Vector.fill(elementCount)(BigInt(0))
    else
      val concretes = preds.map(p => lowerValue(p, sew, seed)).toVector
      Vector.tabulate(elementCount)(i => concretes(i % concretes.size))

  /** Lower a per-operand predicate set + envelope to an emit-ready
   *  witness vector for the given role.
   */
  def lowerOperand(
    role:    OperandRole,
    schema:  Schema,
    env:     VTypeEnvelope,
    preds:   Seq[ValuePred],
    profile: OperandWidthProfile = OperandWidthProfile.default,
    seed:    Long                = 0L
  ): Vector[BigInt] =
    // Element count for this operand is envelope.maxElements scaled by
    // the operand's EMUL relative to base LMUL. For simplicity at this
    // stage, use envelope.maxElements; widening / narrowing changes the
    // EEW but the element count for the source-of-truth witness stays
    // at maxElements (emission layer handles per-operand EMUL).
    buildVector(preds, env.vtype.sew, env.maxElements, seed)
