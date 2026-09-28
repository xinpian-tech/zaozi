// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.operation

import org.llvm.mlir.scalalib.capi.ir.{Context, Location, Operation, Type, Value}
import org.llvm.mlir.scalalib.capi.support.HasOperation

import java.lang.foreign.Arena

class Reg(val _operation: Operation)
trait RegApi extends HasOperation[Reg]:
  /** `sv.reg` - declare a SystemVerilog variable of `elementType`. */
  def op(
    elementType: Type,
    name:        String,
    init:        Option[Value],
    location:    Location
  )(
    using Arena,
    Context
  ): Reg

  extension (ref: Reg)
    def result(
      using Arena
    ): Value
end RegApi

class ReadInOut(val _operation: Operation)
trait ReadInOutApi extends HasOperation[ReadInOut]:
  /** `sv.read_inout` - read the current value of an inout object. */
  def op(
    input:    Value,
    location: Location
  )(
    using Arena,
    Context
  ): ReadInOut

  extension (ref: ReadInOut)
    def result(
      using Arena
    ): Value
end ReadInOutApi

class Verbatim(val _operation: Operation)
trait VerbatimApi extends HasOperation[Verbatim]:
  /** `sv.verbatim` - emit inline SystemVerilog with `{{N}}` operand substitutions. */
  def op(
    formatString:  String,
    substitutions: Seq[Value],
    location:      Location
  )(
    using Arena,
    Context
  ): Verbatim
end VerbatimApi
