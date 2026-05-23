// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.notestfloat3

import me.jiuyang.rvprobe.rvv.Schema
import me.jiuyang.rvprobe.rvv.pred.FpValuePred
import me.jiuyang.rvprobe.rvv.vtype.Sew

/** A single FP test case for an instruction whose upstream toml carries
 *  `notestfloat3 = true`. The case holds the raw FP tokens as they
 *  appear in the curator's hand-written `[tests]` arrays (named tokens
 *  like `"smallest_normal_float"` or decimal literals like `"2.5"`),
 *  plus a one-line rationale capturing what the curator was testing.
 *
 *  Per DEC-4, FP operand generation for ordinary FP instructions stays
 *  with testfloat3. The `notestfloat3 = true` instructions are the
 *  exception: they come from this Scala source-of-truth instead of
 *  testfloat3 or runtime toml reads (per AC-12 + AC-10).
 */
final case class FpCase(sew: Sew, tokens: List[String], rationale: String):
  /** Re-classify each token through `FpValuePred` so the case set
   *  remains traceable to the named-token vocabulary. Any token that
   *  classifies as `FpLit` indicates either (a) a curator addition
   *  this vocabulary hasn't named yet, or (b) a typo in the port.
   */
  def classifyTokens: List[List[FpValuePred]] = tokens.map(FpValuePred.classify)

/** Base trait for the 2 `notestfloat3 = true` instruction case-set
 *  sources committed under `rvprobe/src/rvv/notestfloat3/`. Each
 *  subtype declares the upstream schema, instruction name, and the
 *  full per-SEW case list (typically 9 cases each at sew16/sew32/sew64).
 */
trait NotestFloat3InsnSource:
  def name:   String
  def schema: Schema
  def cases:  List[FpCase]

  def casesAt(sew: Sew): List[FpCase] = cases.filter(_.sew == sew)

  /** Convenience for tests / driver: every case-token classifies into
   *  a named `FpValuePred` (not `FpLit`).
   */
  def allTokensNamed: Boolean =
    cases.forall(c => c.classifyTokens.forall(_.forall {
      case _: FpValuePred.FpLit => false
      case _                    => true
    }))
