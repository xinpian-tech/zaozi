// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.firrtl

import org.llvm.circt.CAPI.circtFirrtlCreateLinkCircuitsPass
import org.llvm.mlir.scalalib.capi.pass.Pass
import org.llvm.mlir.scalalib.capi.support.{*, given}

import java.lang.foreign.Arena

given LinkCircuitsPassApi with
  def createLinkCircuitsPass(
    baseCircuit: String,
    noMangle:    Boolean
  )(
    using arena: Arena
  ): Pass =
    Pass(circtFirrtlCreateLinkCircuitsPass(arena, baseCircuit.toStringRef.segment, noMangle))
end given
