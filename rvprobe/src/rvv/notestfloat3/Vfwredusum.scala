// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.notestfloat3

import me.jiuyang.rvprobe.rvv.Schema
import me.jiuyang.rvprobe.rvv.vtype.Sew

/** Scala source-of-truth for `vfwredusum.vs` test case set. Ported from
 *  `riscv-vector-tests/configs/v/vfwredusum.vs.toml` (upstream pin via
 *  the matching `audit/snapshots/v/vfwredusum.vs.json` contentHash).
 *
 *  This is the widening reduction variant: dest EEW is `2 * SEW`. The
 *  case-tuples themselves are SEW-aligned (token interpretation at the
 *  source SEW); the widening happens during emission (task13). Same
 *  upstream-curated 9-row set per SEW as `Vfredusum`, with the sole
 *  difference that fsew16 uses `1.5` / `-1235.5` instead of `1.1` /
 *  `-1235.1` (i.e., already-terminating .5-fraction values throughout).
 */
object Vfwredusum extends NotestFloat3InsnSource:
  val name:   String = "vfwredusum.vs"
  val schema: Schema = Schema.VdVs2Vs1Vm

  val cases: List[FpCase] = List(
    // ---- fsew16 ----
    FpCase(Sew.Sew16, List("2.5", "1.0"),                                 "common positive >1 with .5 fraction + unity"),
    FpCase(Sew.Sew16, List("-1235.5", "1.5"),                             "arbitrary negative magnitude + .5-fraction positive"),
    FpCase(Sew.Sew16, List("1.5", "-1235.5"),                             "swap-order of the above"),
    FpCase(Sew.Sew16, List("-1.0", "-2.0"),                               "both negatives"),
    FpCase(Sew.Sew16, List("nan", "-nan"),                                "NaN-pair propagation"),
    FpCase(Sew.Sew16, List("inf", "-inf"),                                "inf-pair (overflow-arithmetic edge)"),
    FpCase(Sew.Sew16, List("quiet_nan", "signaling_nan"),                 "quiet vs signaling NaN coexistence"),
    FpCase(Sew.Sew16, List("smallest_nonzero_float", "largest_subnormal_float"), "subnormal range boundary"),
    FpCase(Sew.Sew16, List("-smallest_nonzero_float", "-largest_subnormal_float"), "negated subnormal range boundary"),
    // ---- fsew32 ----
    FpCase(Sew.Sew32, List("2.5", "1.0"),                                 "common positive >1 with .5 fraction + unity"),
    FpCase(Sew.Sew32, List("-1235.5", "1.5"),                             "arbitrary negative magnitude + .5-fraction positive"),
    FpCase(Sew.Sew32, List("1.5", "-1235.5"),                             "swap-order of the above"),
    FpCase(Sew.Sew32, List("-1.0", "-2.0"),                               "both negatives"),
    FpCase(Sew.Sew32, List("nan", "-nan"),                                "NaN-pair propagation"),
    FpCase(Sew.Sew32, List("inf", "-inf"),                                "inf-pair"),
    FpCase(Sew.Sew32, List("quiet_nan", "signaling_nan"),                 "quiet vs signaling NaN coexistence"),
    FpCase(Sew.Sew32, List("smallest_nonzero_float", "largest_subnormal_float"), "subnormal range boundary"),
    FpCase(Sew.Sew32, List("-smallest_nonzero_float", "-largest_subnormal_float"), "negated subnormal range boundary"),
    // ---- fsew64 ----
    FpCase(Sew.Sew64, List("2.5", "1.0"),                                 "common positive >1 with .5 fraction + unity"),
    FpCase(Sew.Sew64, List("-1235.5", "1.5"),                             "arbitrary negative magnitude + .5-fraction positive"),
    FpCase(Sew.Sew64, List("1.5", "-1235.5"),                             "swap-order of the above"),
    FpCase(Sew.Sew64, List("-1.0", "-2.0"),                               "both negatives"),
    FpCase(Sew.Sew64, List("nan", "-nan"),                                "NaN-pair propagation"),
    FpCase(Sew.Sew64, List("inf", "-inf"),                                "inf-pair"),
    FpCase(Sew.Sew64, List("quiet_nan", "signaling_nan"),                 "quiet vs signaling NaN coexistence"),
    FpCase(Sew.Sew64, List("smallest_nonzero_float", "largest_subnormal_float"), "subnormal range boundary"),
    FpCase(Sew.Sew64, List("-smallest_nonzero_float", "-largest_subnormal_float"), "negated subnormal range boundary"))
