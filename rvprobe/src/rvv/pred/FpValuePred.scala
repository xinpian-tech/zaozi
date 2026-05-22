// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.pred

/** Element-level FP predicates. Upstream tomls express FP values as named
 *  tokens (`"smallest_normal_float"`, `"nan"`, `"-inf"`) plus decimal
 *  literals. Each named token maps 1:1 to a predicate; decimals fall into
 *  `FpDecimal(rationale)` with a curated annotation.
 *
 *  Per DEC-4, runtime FP operand generation stays with testfloat3; this
 *  vocabulary serves the audit pass and the 2 `notestfloat3=true`
 *  instructions whose case sets live in Scala source-of-truth.
 */
enum FpValuePred:
  case PosZero
  case NegZero
  case PosInf
  case NegInf
  case Nan
  case NegNan
  case QuietNan
  case SignalingNan
  case SmallestNonzero
  case NegSmallestNonzero
  case LargestSubnormal
  case NegLargestSubnormal
  case SmallestNormal
  case NegSmallestNormal
  case MaxFinite
  case NegMaxFinite
  case FpDecimal(literal: String, rationale: String)
  case FpLit(literal: String, rationale: String)

object FpValuePred:
  val caseNames: List[String] = List(
    "PosZero", "NegZero", "PosInf", "NegInf", "Nan", "NegNan", "QuietNan",
    "SignalingNan", "SmallestNonzero", "NegSmallestNonzero",
    "LargestSubnormal", "NegLargestSubnormal", "SmallestNormal",
    "NegSmallestNormal", "MaxFinite", "NegMaxFinite", "FpDecimal", "FpLit")

  /** Classify a single FP token as it appears in upstream `.toml` arrays.
   *  Tokens are the literal strings the toml stores: `"2.5"`,
   *  `"smallest_normal_float"`, `"nan"`, etc.
   */
  def classify(token: String): List[FpValuePred] =
    val t = token.trim.stripPrefix("\"").stripSuffix("\"").trim
    t match
      case "0.0" | "+0.0"                  => List(PosZero)
      case "-0.0"                          => List(NegZero)
      case "inf" | "+inf"                  => List(PosInf)
      case "-inf"                          => List(NegInf)
      case "nan"                           => List(Nan)
      case "-nan"                          => List(NegNan)
      case "quiet_nan"                     => List(QuietNan)
      case "signaling_nan"                 => List(SignalingNan)
      case "smallest_nonzero_float"        => List(SmallestNonzero)
      case "-smallest_nonzero_float"       => List(NegSmallestNonzero)
      case "largest_subnormal_float"       => List(LargestSubnormal)
      case "-largest_subnormal_float"      => List(NegLargestSubnormal)
      case "smallest_normal_float"         => List(SmallestNormal)
      case "-smallest_normal_float"        => List(NegSmallestNormal)
      case "max_float"                     => List(MaxFinite)
      case "-max_float"                    => List(NegMaxFinite)
      case other if isDecimalLike(other)   =>
        List(FpDecimal(other, rationaleForDecimal(other)))
      case other                           =>
        List(FpLit(other, s"unclassified FP token: '$other'"))

  private def isDecimalLike(s: String): Boolean =
    s.headOption.exists(c => c == '-' || c == '+' || c.isDigit) &&
      s.exists(_.isDigit)

  private def rationaleForDecimal(d: String): String = d match
    case "2.5"          => "common positive >1 with .5 fraction"
    case "1.0"          => "unity"
    case "-1.0"         => "negative unity"
    case "-2.0"         => "negative even integer"
    case "1.1"          => "near-unity positive with non-terminating binary fraction"
    case "-1235.1"      => "arbitrary negative magnitude with .1 fraction"
    case "3.14159265"   => "approximate pi (precision boundary)"
    case "0.00000001"   => "tiny positive (denormalization boundary)"
    case "0.002"        => "small positive with non-terminating binary fraction"
    case _              => s"curated decimal literal: $d"
