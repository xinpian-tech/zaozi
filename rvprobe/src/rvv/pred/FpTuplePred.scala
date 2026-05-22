// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.pred

/** FP tuple-level intents. Upstream FP test rows pair specific FP corners
 *  to test pair-wise behaviors: NaN propagation, mixed-sign zeros, inf
 *  arithmetic, subnormal boundaries.
 */
enum FpTuplePred:
  case NaNPair
  case QuietVsSignalingNan
  case InfPair
  case MixedSignZeros
  case SubnormalBoundary
  case NormalBoundary
  case NormalPair
  case Lit(rationale: String)

object FpTuplePred:
  val caseNames: List[String] = List(
    "NaNPair", "QuietVsSignalingNan", "InfPair", "MixedSignZeros",
    "SubnormalBoundary", "NormalBoundary", "NormalPair", "Lit")

  def classify(row: List[FpValuePred]): List[FpTuplePred] =
    val builders = List.newBuilder[FpTuplePred]
    val classes  = row.toSet

    if hasNan(classes) && hasNegNan(classes) then builders += NaNPair
    if classes.contains(FpValuePred.QuietNan) && classes.contains(FpValuePred.SignalingNan) then
      builders += QuietVsSignalingNan
    if classes.contains(FpValuePred.PosInf) && classes.contains(FpValuePred.NegInf) then builders += InfPair
    if classes.contains(FpValuePred.PosZero) && classes.contains(FpValuePred.NegZero) then
      builders += MixedSignZeros

    val subnormalTokens = Set(
      FpValuePred.SmallestNonzero,
      FpValuePred.LargestSubnormal,
      FpValuePred.NegSmallestNonzero,
      FpValuePred.NegLargestSubnormal)
    if row.exists(p => subnormalTokens.contains(p)) && row.count(p => subnormalTokens.contains(p)) >= 2 then
      builders += SubnormalBoundary

    val normalBoundaryTokens = Set(
      FpValuePred.SmallestNormal,
      FpValuePred.MaxFinite,
      FpValuePred.NegSmallestNormal,
      FpValuePred.NegMaxFinite)
    if row.exists(p => normalBoundaryTokens.contains(p)) && row.count(p => normalBoundaryTokens.contains(p)) >= 2 then
      builders += NormalBoundary

    val decimalCount = row.count {
      case _: FpValuePred.FpDecimal => true
      case _                        => false
    }
    if decimalCount >= 2 && row.forall {
      case _: FpValuePred.FpDecimal => true
      case _                        => false
    }
    then builders += NormalPair

    val matches = builders.result().distinct
    if matches.isEmpty then List(Lit(s"unclassified FP tuple: ${row.mkString(",")}"))
    else matches

  private def hasNan(s: Set[FpValuePred]): Boolean =
    s.contains(FpValuePred.Nan) || s.contains(FpValuePred.QuietNan) || s.contains(FpValuePred.SignalingNan)
  private def hasNegNan(s: Set[FpValuePred]): Boolean = s.contains(FpValuePred.NegNan)
