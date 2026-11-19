// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package org.llvm.circt.scalalib.capi.dialect.firrtl

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.pass.Pass
import org.llvm.mlir.scalalib.capi.support.{*, given}

given PassApi with
  inline def linkCircuitsPass(
    baseCircuit: String,
    noMangle:    Boolean
  )(
    using zone:  Zone
  ): Pass = Pass(
    nativeResult[MlirStructs.MlirPass](out =>
      circtApi.circtFirrtlCreateLinkCircuitsPass(baseCircuit.toStringRef.native, noMangle)(out)
    )
  )
end given
