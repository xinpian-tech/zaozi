// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.operation

import org.llvm.circt.scalalib.dialect.hw.operation.Port
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, Operation, Type, Value}
import org.llvm.mlir.scalalib.capi.support.HasOperation

import java.lang.foreign.Arena

enum EventControl:
  case PosEdge, NegEdge, Edge

class Always(val _operation: Operation)
trait AlwaysApi extends HasOperation[Always]:
  def op(
    events:   Seq[EventControl],
    clocks:   Seq[Value],
    location: Location
  )(
    using Arena,
    Context
  ): Always
  extension (ref: Always)
    def block(
      using Arena
    ): Block

class Func(val _operation: Operation)
trait FuncApi extends HasOperation[Func]:
  /** Declares an SV function; `returnPort` identifies its explicit return value. */
  def op(
    symbol:      String,
    ports:       Seq[Port],
    returnPort:  Option[Int],
    verilogName: Option[String],
    location:    Location
  )(
    using Arena,
    Context
  ): Func

class FuncDPIImport(val _operation: Operation)
trait FuncDPIImportApi extends HasOperation[FuncDPIImport]:
  def op(
    callee:      String,
    linkageName: Option[String],
    location:    Location
  )(
    using Arena,
    Context
  ): FuncDPIImport

class FuncCallProcedural(val _operation: Operation)
trait FuncCallProceduralApi extends HasOperation[FuncCallProcedural]:
  def op(
    callee:      String,
    inputs:      Seq[Value],
    resultTypes: Seq[Type],
    location:    Location
  )(
    using Arena,
    Context
  ): FuncCallProcedural

class Initial(val _operation: Operation)
trait InitialApi extends HasOperation[Initial]:
  def op(
    location: Location
  )(
    using Arena,
    Context
  ):   Initial
  extension (ref: Initial)
    def block(
      using Arena
    ): Block

class If(val _operation: Operation)
trait IfApi extends HasOperation[If]:
  def op(
    condition: Value,
    location:  Location
  )(
    using Arena,
    Context
  ): If
  extension (ref: If)
    def thenBlock(
      using Arena
    ): Block
    def elseBlock(
      using Arena
    ): Block

class Case(val _operation: Operation)
trait CaseApi extends HasOperation[Case]:
  /** Exact integer patterns followed by one default region. */
  def op(
    selector: Value,
    patterns: Seq[BigInt],
    location: Location
  )(
    using Arena,
    Context
  ):   Case
  extension (ref: Case)
    def block(
      index: Int
    )(
      using Arena
    ): Block

class Wire(val _operation: Operation)
trait WireApi extends HasOperation[Wire]:
  def op(
    elementType: Type,
    name:        String,
    location:    Location
  )(
    using Arena,
    Context
  ): Wire

class Assign(val _operation: Operation)
trait AssignApi extends HasOperation[Assign]:
  def op(
    destination: Value,
    source:      Value,
    location:    Location
  )(
    using Arena,
    Context
  ): Assign

class BPAssign(val _operation: Operation)
trait BPAssignApi extends HasOperation[BPAssign]:
  def op(
    destination: Value,
    source:      Value,
    location:    Location
  )(
    using Arena,
    Context
  ): BPAssign

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

class PAssign(val _operation: Operation)
trait PAssignApi extends HasOperation[PAssign]:
  /** `sv.passign` - schedule a nonblocking assignment in the current procedure. */
  def op(
    destination: Value,
    source:      Value,
    location:    Location
  )(
    using Arena,
    Context
  ): PAssign
end PAssignApi

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
