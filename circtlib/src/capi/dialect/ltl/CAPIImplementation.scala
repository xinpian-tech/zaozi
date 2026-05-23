// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.ltl

import org.llvm.circt.CAPI.{LTL_CLOCK_EDGE_BOTH, LTL_CLOCK_EDGE_NEG, LTL_CLOCK_EDGE_POS}

given LTLClockEdgeApi with
  extension (int: Int)
    override inline def fromNative: LTLClockEdge = int match
      case i if i == LTL_CLOCK_EDGE_POS()  => LTLClockEdge.Pos
      case i if i == LTL_CLOCK_EDGE_NEG()  => LTLClockEdge.Neg
      case i if i == LTL_CLOCK_EDGE_BOTH() => LTLClockEdge.Both
  extension (ref: LTLClockEdge)
    inline def toNative: Int = ref match
      case LTLClockEdge.Pos  => LTL_CLOCK_EDGE_POS()
      case LTLClockEdge.Neg  => LTL_CLOCK_EDGE_NEG()
      case LTLClockEdge.Both => LTL_CLOCK_EDGE_BOTH()
    inline def sizeOf:   Int = 4
end given
