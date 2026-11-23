// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.ltl

import org.llvm.circt.raw.enumerations as CirctEnums
given LTLClockEdgeApi with
  extension (int: Int)
    override inline def fromNative: LTLClockEdge = int match
      case i if i == CirctEnums.LTLClockEdge.LTL_CLOCK_EDGE_POS.int  => LTLClockEdge.Pos
      case i if i == CirctEnums.LTLClockEdge.LTL_CLOCK_EDGE_NEG.int  => LTLClockEdge.Neg
      case i if i == CirctEnums.LTLClockEdge.LTL_CLOCK_EDGE_BOTH.int => LTLClockEdge.Both
  extension (ref: LTLClockEdge)
    inline def toNative: Int = ref match
      case LTLClockEdge.Pos  => CirctEnums.LTLClockEdge.LTL_CLOCK_EDGE_POS.int
      case LTLClockEdge.Neg  => CirctEnums.LTLClockEdge.LTL_CLOCK_EDGE_NEG.int
      case LTLClockEdge.Both => CirctEnums.LTLClockEdge.LTL_CLOCK_EDGE_BOTH.int
    inline def sizeOf:   Int = 4
end given
