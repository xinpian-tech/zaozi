// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.unittest

import me.jiuyang.rvprobe.rvv.Schema
import me.jiuyang.rvprobe.rvv.eew.OperandWidthProfile
import me.jiuyang.rvprobe.rvv.pred.*

/** Per-instruction declaration on the rvprobe side. Each upstream
 *  `configs/<ext>/<name>.toml` corresponds to one RvvInsn at the
 *  rvprobe layer, keyed by (extension, name) to support
 *  duplicate-name disambiguation per AC-14 (`vfncvt.f.f.w` exists in
 *  both `v/` and `zvfhmin/`).
 *
 *  See `rvprobe/plan-migrate-rvv-tests.md`'s "What `vadd.vv` looks like"
 *  sketch for the intended user-facing form. The Driver walks all
 *  declared insns and emits one `.S` per (insn × SEW × LMUL × ...)
 *  combination per the contract.
 */
final case class RvvInsn(
  name:            String,
  extension:       String,
  schema:          Schema,
  widthProfile:    OperandWidthProfile = OperandWidthProfile.default,
  vxrm:            Boolean             = false,
  vxsat:           Boolean             = false,
  notestfloat3:    Boolean             = false,
  /** Per-operand element-level predicates. Empty for FP instructions
   *  whose operand source is testfloat3 (DEC-4).
   */
  intPredicates:   Map[String, List[ValuePred]]    = Map.empty,
  /** Operand-tuple level predicates (algebraic edge cases). */
  tuplePredicates: List[TuplePred]                 = Nil,
  /** Optional indexed EEW for indexed load/store schemas (vluxei8 ⇒ 8,
   *  vluxei32 ⇒ 32, etc).
   */
  indexedEew:      Option[Int]                     = None,
  /** Optional NFIELDS for segmented load/store schemas. */
  nfields:         Int                             = 1,
  /** Per AC-2: source TOML path under upstream
   *  `riscv-vector-tests/configs/<ext>/<name>.toml`. Empty for
   *  hand-authored declarations (none currently).
   */
  sourceToml:      String                          = "")

object RvvInsn:
  /** Stable disambiguator: (extension, name). Two upstream tomls with
   *  the same name in different extensions (`vfncvt.f.f.w` in v + in
   *  zvfhmin) produce distinct RvvInsn keys.
   */
  def key(insn: RvvInsn): (String, String) = (insn.extension, insn.name)

  /** Stage-1 output filename for an insn. Matches the upstream
   *  Makefile contract: dot-to-underscore, split-indexed.
   */
  def stageFileName(insn: RvvInsn, splitIndex: Int): String =
    val flat = insn.name.replace('.', '_')
    s"${flat}_${insn.extension}-${splitIndex}.S"
