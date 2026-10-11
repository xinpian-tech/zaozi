// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.tpe

import org.llvm.mlir.scalalib.capi.ir.{Context, Type}
import java.lang.foreign.Arena

/** SV type construction. SV.h exposes no type constructors; the implementation uses the MLIR type parser. */
trait TypeApi:
  def getNet(
    element: Type
  )(
    using Arena,
    Context
  ): Type
  def getVar(
    element: Type
  )(
    using Arena,
    Context
  ): Type
  def getUnpackedOpenArray(
    element: Type
  )(
    using Arena,
    Context
  ): Type
  def getInterface(
    symbol: String
  )(
    using Arena,
    Context
  ): Type
  def getModport(
    interfaceSymbol: String,
    modportSymbol:   String
  )(
    using Arena,
    Context
  ): Type

end TypeApi
