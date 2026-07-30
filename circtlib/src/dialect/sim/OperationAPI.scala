// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sim.operation

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, Operation, Type, Value}
import org.llvm.mlir.scalalib.capi.support.HasOperation

import java.lang.foreign.Arena

class FormatLiteral(val _operation: Operation)
trait FormatLiteralApi extends HasOperation[FormatLiteral]:
  /** `sim.fmt.literal` — a constant ASCII fragment. */
  def op(
    literal:  String,
    location: Location
  )(
    using Arena,
    Context
  ): FormatLiteral

  extension (ref: FormatLiteral)
    def result(
      using Arena
    ): Value
end FormatLiteralApi

class FormatDec(val _operation: Operation)
trait FormatDecApi extends HasOperation[FormatDec]:
  /** `sim.fmt.dec` — format an integer as decimal. */
  def op(
    value:    Value,
    isSigned: Boolean,
    location: Location
  )(
    using Arena,
    Context
  ): FormatDec

  extension (ref: FormatDec)
    def result(
      using Arena
    ): Value
end FormatDecApi

class FormatHex(val _operation: Operation)
trait FormatHexApi extends HasOperation[FormatHex]:
  /** `sim.fmt.hex` — format an integer as hexadecimal. */
  def op(
    value:       Value,
    isUppercase: Boolean,
    location:    Location
  )(
    using Arena,
    Context
  ): FormatHex

  extension (ref: FormatHex)
    def result(
      using Arena
    ): Value
end FormatHexApi

class FormatChar(val _operation: Operation)
trait FormatCharApi extends HasOperation[FormatChar]:
  /** `sim.fmt.char` — format an integer as a single character. */
  def op(
    value:    Value,
    location: Location
  )(
    using Arena,
    Context
  ): FormatChar

  extension (ref: FormatChar)
    def result(
      using Arena
    ): Value
end FormatCharApi

class FormatCurrentTime(val _operation: Operation)
trait FormatCurrentTimeApi extends HasOperation[FormatCurrentTime]:
  /** `sim.fmt.current_time` — resolves to simulation time when printed. */
  def op(
    location: Location
  )(
    using Arena,
    Context
  ): FormatCurrentTime

  extension (ref: FormatCurrentTime)
    def result(
      using Arena
    ): Value
end FormatCurrentTimeApi

class FormatConcat(val _operation: Operation)
trait FormatConcatApi extends HasOperation[FormatConcat]:
  /** `sim.fmt.concat` — concatenate format strings left to right. */
  def op(
    inputs:   Seq[Value],
    location: Location
  )(
    using Arena,
    Context
  ): FormatConcat

  extension (ref: FormatConcat)
    def result(
      using Arena
    ): Value
end FormatConcatApi
