// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.pred

import me.jiuyang.rvprobe.rvv.vtype.Sew

/** Element-level integer predicates. Each predicate identifies a curator
 *  intent behind a specific element value in an upstream `.toml` tuple.
 *  Per DEC-1, `Lit(value, rationale)` is a permanent named escape hatch
 *  for genuinely arbitrary corners — not audit debt.
 */
enum ValuePred:
  case Zero
  case One
  case MinusOne
  case AllOnes(sew: Sew)
  case MaxSigned(sew: Sew)
  case MinSigned(sew: Sew)
  case MaxUnsigned(sew: Sew)
  case SignBitOnly(sew: Sew)
  case NearMaxSigned(sew: Sew, offset: Int)
  case SmallSigned(value: Int)
  case BitPattern(value: BigInt, rationale: String)
  case Random(sew: Sew, seed: Long)
  case Lit(value: BigInt, rationale: String)

object ValuePred:
  /** All case names declared in the sealed family. Used by the backward
   *  audit report (`TomlIntent.renderBackwardReport`) to detect dead
   *  vocabulary entries. Keep in sync with the enum definition above.
   */
  val caseNames: List[String] = List(
    "Zero", "One", "MinusOne", "AllOnes", "MaxSigned", "MinSigned",
    "MaxUnsigned", "SignBitOnly", "NearMaxSigned",
    "SmallSigned", "BitPattern", "Random", "Lit")

  /** Classify a single concrete element value at the given SEW into one
   *  or more `ValuePred` matches. Returns `Lit` with an auto-rationale
   *  for un-named values so the audit pass never silently drops a
   *  curator literal.
   */
  def classify(value: BigInt, sew: Sew): List[ValuePred] =
    val width = sew.bits
    val mask  = (BigInt(1) << width) - 1
    val v     = value & mask
    val builders = List.newBuilder[ValuePred]
    if v == BigInt(0) then builders += Zero
    if v == BigInt(1) then builders += One
    if v == mask then
      builders += AllOnes(sew)
      builders += MinusOne
    val maxSigned = (BigInt(1) << (width - 1)) - 1
    val minSigned = BigInt(1) << (width - 1) // 2's-complement bit pattern
    if v == maxSigned then builders += MaxSigned(sew)
    if v == minSigned then
      builders += MinSigned(sew)
      builders += SignBitOnly(sew)
    if v == mask then builders += MaxUnsigned(sew)
    // SmallSigned: arithmetic value in [-16, 16] excluding the cases above
    val signedValue =
      if v >= (BigInt(1) << (width - 1)) then v - (BigInt(1) << width) else v
    if signedValue.abs <= BigInt(16) && signedValue != BigInt(0) && signedValue != BigInt(1) &&
      signedValue != BigInt(-1)
    then builders += SmallSigned(signedValue.toInt)
    // NearMaxSigned: small positive offset below the signed-max boundary
    val deltaMax = maxSigned - v
    if deltaMax > BigInt(0) && deltaMax <= BigInt(16) then builders += NearMaxSigned(sew, deltaMax.toInt)
    // Known curated bit patterns (1/7 fixed-point, recurring divisor edges).
    if v == BigInt("6db6db6db6db6db7", 16) then
      builders += BitPattern(v, "1/7 fixed-point representation; recurring divisor edge")
    if v == BigInt("6db7", 16) || v == BigInt("b6db6db7", 16) || v == BigInt("b7", 16) then
      builders += BitPattern(v, "1/7 fixed-point truncated to lower SEW")
    val matches = builders.result()
    if matches.isEmpty then
      List(Lit(v, s"unclassified literal at sew=$width: 0x${v.toString(16)}"))
    else matches
