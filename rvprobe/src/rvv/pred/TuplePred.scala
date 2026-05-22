// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.pred

import me.jiuyang.rvprobe.rvv.vtype.Sew

/** Operand-tuple level predicates. These capture *algebraic* intent across
 *  multiple operands of a single test row — e.g. "max signed + small
 *  positive" (overflow corner), "min signed / -1" (signed-division
 *  overflow), "shift by SEW-1" (max legal shift amount).
 *
 *  Tuples come straight from upstream `.toml` rows, where one row groups
 *  the inputs of a single instruction execution.
 */
enum TuplePred:
  case AllZero
  case AllAllOnes(sew: Sew)
  case AllSame(value: BigInt, rationale: String)
  case ZeroPlusSmall(other: Int)
  case MaxPlusOne(sew: Sew)
  case MaxPlusSmallPositive(sew: Sew, other: Int)
  case AllOnesPlusAllOnes(sew: Sew)
  case NegSmallPlusPosSmall(neg: Int, pos: Int)
  case ShiftByZero
  case ShiftByOne
  case ShiftBySewMinus1(sew: Sew)
  case ShiftBySewOrAbove(sew: Sew)
  case DivByZero(sew: Sew)
  case MinSignedDivZero(sew: Sew)
  case ZeroDivAnything(sew: Sew)
  case BitPatternPair(rationale: String)
  case Lit(rows: List[BigInt], rationale: String)

object TuplePred:
  val caseNames: List[String] = List(
    "AllZero", "AllAllOnes", "AllSame", "ZeroPlusSmall",
    "MaxPlusOne", "MaxPlusSmallPositive",
    "AllOnesPlusAllOnes", "NegSmallPlusPosSmall", "ShiftByZero",
    "ShiftByOne", "ShiftBySewMinus1", "ShiftBySewOrAbove", "DivByZero",
    "MinSignedDivZero", "ZeroDivAnything",
    "BitPatternPair", "Lit")

  /** Classify a tuple of element values at a given SEW. The tuple comes
   *  from an upstream toml row (typically 1 or 2 elements; up to 3 for
   *  some multi-operand instructions). The "shift" predicates only fire
   *  when the tuple is a shift family — caller decides whether to invoke
   *  this method on a known-shift toml.
   */
  def classify(row: List[BigInt], sew: Sew, hint: ClassifyHint = ClassifyHint.Generic): List[TuplePred] =
    val width = sew.bits
    val mask  = (BigInt(1) << width) - 1
    val ms    = (BigInt(1) << (width - 1)) - 1 // MaxSigned
    val mn    = BigInt(1) << (width - 1)       // MinSigned (bit pattern)
    val all1  = mask                            // AllOnes
    val r     = row.map(_ & mask)

    def signed(v: BigInt): BigInt =
      if v >= mn then v - (BigInt(1) << width) else v

    val builders = List.newBuilder[TuplePred]

    // ---- Order-independent shape predicates ----
    if r.forall(_ == BigInt(0)) then builders += AllZero
    if r.forall(_ == all1) then builders += AllAllOnes(sew)
    if r.distinct.size == 1 && r.nonEmpty && r.head != BigInt(0) && r.head != all1 then
      builders += AllSame(r.head, s"all operands = 0x${r.head.toString(16)}")

    // ---- Two-operand specific patterns ----
    if r.size == 2 then
      val (a, b) = (r(0), r(1))
      // Zero + small (other != 0)
      if a == BigInt(0) && signed(b).abs > BigInt(0) && signed(b).abs <= BigInt(16) then
        builders += ZeroPlusSmall(signed(b).toInt)
      if b == BigInt(0) && signed(a).abs > BigInt(0) && signed(a).abs <= BigInt(16) then
        builders += ZeroPlusSmall(signed(a).toInt)

      // Hint-gated arithmetic patterns
      hint match
        case ClassifyHint.Add =>
          if (a == ms && b == BigInt(1)) || (b == ms && a == BigInt(1)) then builders += MaxPlusOne(sew)
          if a == all1 && b == all1 then builders += AllOnesPlusAllOnes(sew)
          if (a == ms && signed(b) > BigInt(0) && signed(b) <= BigInt(16)) ||
            (b == ms && signed(a) > BigInt(0) && signed(a) <= BigInt(16))
          then
            val small = if a == ms then signed(b).toInt else signed(a).toInt
            builders += MaxPlusSmallPositive(sew, small)
          if signed(a) > BigInt(0) && signed(a) <= BigInt(16) &&
            signed(b) < BigInt(0) && signed(b) >= BigInt(-16)
          then builders += NegSmallPlusPosSmall(signed(b).toInt, signed(a).toInt)
          if signed(b) > BigInt(0) && signed(b) <= BigInt(16) &&
            signed(a) < BigInt(0) && signed(a) >= BigInt(-16)
          then builders += NegSmallPlusPosSmall(signed(a).toInt, signed(b).toInt)
        case ClassifyHint.Divide =>
          if b == BigInt(0) then builders += DivByZero(sew)
          if a == BigInt(0) then builders += ZeroDivAnything(sew)
          if a == mn && b == BigInt(0) then builders += MinSignedDivZero(sew)
        case ClassifyHint.Shift =>
          // For shifts: the FIRST operand is the shift amount in upstream
          // tomls, the SECOND is the data. (0x1f, X) = shift by 31 etc.
          val amount = a
          if amount == BigInt(0) then builders += ShiftByZero
          if amount == BigInt(1) then builders += ShiftByOne
          if amount == BigInt(width - 1) then builders += ShiftBySewMinus1(sew)
          if amount >= BigInt(width) then builders += ShiftBySewOrAbove(sew)
        case ClassifyHint.Generic =>
          ()

    // Known curated bit-pattern pairs (1/7 recurring patterns)
    val sevenths = Set(
      BigInt("6db6db6db6db6db7", 16),
      BigInt("b6db6db7", 16),
      BigInt("6db7", 16),
      BigInt("b7", 16))
    if r.exists(sevenths.contains) then
      builders += BitPatternPair("contains 1/7 fixed-point pattern from upstream curator")

    val matches = builders.result().distinct
    if matches.isEmpty then List(Lit(r, s"unclassified row at sew=$width: ${r.map(v => s"0x${v.toString(16)}")}"))
    else matches

enum ClassifyHint:
  case Generic
  case Add
  case Divide
  case Shift
