// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package org.llvm.mlir.scalalib.capi.dialect.func

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Module, Operation, Type, given}

import java.lang.foreign.Arena

trait DialectApi:
  inline def loadDialect(
  )(
    using arena: Arena,
    context:     Context
  ): Unit
end DialectApi

class Func(val _operation: Operation)
trait FuncApi extends HasOperation[Func]:
  inline def op(
    symName:     String
    // funcType: Type,
    // symVisibility: String,
    // argAttrs: Seq[NamedAttribute],
    // resAttrs: Seq[NamedAttribute],
    // noInline: Boolean,
  )(
    using arena: Arena,
    context:     Context
  ): Func

  def op(
    symName:        String,
    inputs:         Seq[Type],
    results:        Seq[Type],
    emitCInterface: Boolean
  )(
    using Arena,
    Context
  ): Func

  extension (c: Func)
    inline def block(
      using Arena
    ): Block
    inline def appendToModule(
    )(
      using arena: Arena,
      module:      Module
    ): Unit
end FuncApi
