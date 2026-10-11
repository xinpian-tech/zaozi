// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.tpe

import org.llvm.circt.scalalib.dialect.sv.SVEncoding
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}
import java.lang.foreign.Arena

given TypeApi with
  def getNet(
    element: Type
  )(
    using Arena,
    Context
  ): Type = SVEncoding.tpe(s"!sv.net<${SVEncoding.show(element)}>")
  def getVar(
    element: Type
  )(
    using Arena,
    Context
  ): Type = SVEncoding.tpe(s"!sv.var<${SVEncoding.show(element)}>")
  def getUnpackedOpenArray(
    element: Type
  )(
    using Arena,
    Context
  ): Type = SVEncoding.tpe(s"!sv.open_uarray<${SVEncoding.show(element)}>")
  def getInterface(
    symbol: String
  )(
    using Arena,
    Context
  ): Type = SVEncoding.tpe(s"!sv.interface<@${SVEncoding.quote(symbol)}>")
  def getModport(
    interfaceSymbol: String,
    modportSymbol:   String
  )(
    using Arena,
    Context
  ): Type =
    SVEncoding.tpe(s"!sv.modport<@${SVEncoding.quote(interfaceSymbol)}::@${SVEncoding.quote(modportSymbol)}>")
end given
