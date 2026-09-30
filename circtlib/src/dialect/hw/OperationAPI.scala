// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.hw.operation

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, Operation, Type, Value}
import org.llvm.mlir.scalalib.capi.support.HasOperation

import java.lang.foreign.Arena

enum PortDirection:
  case Input, Output, InOut

final case class Port(name: String, direction: PortDirection, tpe: Type)

class Module(val _operation: Operation)
trait ModuleApi extends HasOperation[Module]:
  /** `hw.module` - a SystemVerilog-compatible hardware module definition. */
  def op(
    symbol:   String,
    ports:    Seq[Port],
    location: Location
  )(
    using Arena,
    Context
  ): Module

  extension (ref: Module)
    /** Module symbol name. */
    def symbol(
      using Arena
    ): String

    /** Ports in declaration order. */
    def ports(
      using Arena
    ): Seq[Port]

    /** The module body. Input and inout ports are its block arguments. */
    def block(
      using Arena
    ): Block
end ModuleApi

class ModuleExtern(val _operation: Operation)
trait ModuleExternApi extends HasOperation[ModuleExtern]:
  /** `hw.module.extern` - an external module declaration used for symbol and port checking. */
  def op(
    symbol:      String,
    ports:       Seq[Port],
    verilogName: Option[String],
    location:    Location
  )(
    using Arena,
    Context
  ): ModuleExtern
end ModuleExternApi

class Instance(val _operation: Operation)
trait InstanceApi extends HasOperation[Instance]:
  /** `hw.instance` - instantiate a module described by `ports`.
    *
    * `inputs` are ordered like the input and inout entries in `ports`; results are ordered like its output entries.
    */
  def op(
    instanceName: String,
    moduleName:   String,
    ports:        Seq[Port],
    inputs:       Seq[Value],
    location:     Location
  )(
    using Arena,
    Context
  ): Instance

  extension (ref: Instance)
    /** All instance output values in output-port order. */
    def results(
      using Arena
    ): Seq[Value]

    def result(
      index: Int
    )(
      using Arena
    ): Value
end InstanceApi

class Constant(val _operation: Operation)
trait ConstantApi extends HasOperation[Constant]:
  def op(
    value:    BigInt,
    width:    Int,
    location: Location
  )(
    using Arena,
    Context
  ): Constant

class Bitcast(val _operation: Operation)
trait BitcastApi extends HasOperation[Bitcast]:
  def op(
    input:      Value,
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ): Bitcast

class Output(val _operation: Operation)
trait OutputApi extends HasOperation[Output]:
  /** `hw.output` - terminate an `hw.module` body and drive its output ports. */
  def op(
    outputs:  Seq[Value],
    location: Location
  )(
    using Arena,
    Context
  ): Output
end OutputApi
