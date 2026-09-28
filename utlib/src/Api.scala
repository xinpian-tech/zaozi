// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.utlib.macros.testbenchIOSelectDynamic
import me.jiuyang.zaozi.{
  DVInterface,
  DpiArg,
  DpiCallResult,
  DpiFunction,
  Generator,
  HWInterface,
  LayerInterface,
  Parameter
}
import me.jiuyang.zaozi.valuetpe.{BundleField, Data}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Module, Value}

import java.lang.foreign.Arena
import scala.language.dynamics

/** A typed testbench port bound to one simulation-wrapper instance. */
final class TestbenchPort[T <: Data] private[utlib] (
  private val field: BundleField[T],
  private val testbench: Testbench[?]):
  /** Binds a wrapper value to this testbench input. */
  infix def :=(
    value: Value
  )(
    using Arena
  ): Unit = testbench.bind(field, value)

/** Typed access to the driven ports of `I`. */
final class TestbenchIO[I <: HWInterface[?]] private[utlib] (private val testbench: Testbench[I]) extends Dynamic:
  private[utlib] def port[T <: Data](name:       String): TestbenchPort[T]         = testbench.port(name)
  private[utlib] def portOption[T <: Data](name: String): Option[TestbenchPort[T]] = testbench.portOption(name)

  transparent inline def selectDynamic(name: String): Any = ${ testbenchIOSelectDynamic[I]('this, 'name) }

/** Operations and typed IO bindings for one wrapper elaboration, supplied to `TestbenchGenerator.simulation`. */
trait Testbench[I <: HWInterface[?]]:
  def clock:        Value
  def fallingClock: Value
  def io:           TestbenchIO[I]

  /** Declares a DPI function at the builtin module scope. */
  def dpiFunction(
    symbol:    String,
    cName:     Option[String],
    arguments: Seq[DpiArg]
  )(
    using Arena,
    Context,
    Block
  ): DpiFunction

  /** Calls a DPI function on the falling edge of the generated testbench clock. */
  def dpiCall(
    function: DpiFunction,
    inputs:   Seq[Value] = Seq.empty,
    enabled:  Option[Value] = None
  )(
    using Arena,
    Context,
    Block
  ): DpiCallResult

  /** Finishes the simulation when `condition` is true on the generated testbench clock. */
  def finish(
    condition: Value,
    success:   Boolean = true
  )(
    using Arena,
    Context,
    Block
  ): Unit

  private[utlib] def port[T <: Data](name:       String): TestbenchPort[T]
  private[utlib] def portOption[T <: Data](name: String): Option[TestbenchPort[T]]
  private[utlib] def bind(
    field: BundleField[?],
    value: Value
  )(
    using Arena
  ):                                                      Unit

  /** Resolves the instance inputs in HW port order, including the generated clock. */
  private[utlib] def inputValues: Seq[Value]

/** Defines the FIRRTL testbench architecture and the simulation behavior of its wrapper. */
trait TestbenchGenerator[
  PARAM <: Parameter,
  L <: LayerInterface[PARAM],
  I <: HWInterface[PARAM],
  P <: DVInterface[PARAM, L]]
    extends Generator[PARAM, L, I, P]:
  def wrapperName(parameter:   PARAM): String = s"${moduleName(parameter)}Wrapper"
  def clockPeriodNs(parameter: PARAM): Long
  def simulation(parameter:    PARAM): (
    Arena,
    Context,
    Block,
    Testbench[I]
  ) ?=> Unit

/** Builds a unit testbench and exports its IR, DPI interface, or SystemVerilog. */
trait TestbenchGeneratorApi:
  extension [PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    generator: TestbenchGenerator[PARAM, L, I, P]
  )
    /** Links and lowers the FIRRTL inputs, then constructs the simulation wrapper once. The caller owns the returned
      * builtin module and must destroy it before its context.
      */
    def module(
      parameter:     PARAM,
      firrtlModules: Seq[Array[Byte]]
    )(
      using Arena,
      Context
    ): Module

  extension (module: Module)
    /** Returns the complete testbench, including its wrapper, as MLIR bytecode. */
    def toMlirBytecode(
      using Arena
    ): Array[Byte]

    /** Returns the DPI interface declared in the testbench as structured JSON. */
    def toDpiJson(
      using Arena
    ): ujson.Value

    /** Returns SystemVerilog text by lowering a copy, preserving the original module for further conversions. */
    def toVerilog(
      using Arena
    ): String
