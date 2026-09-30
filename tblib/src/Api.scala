// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.tblib

import me.jiuyang.tblib.macros.testbenchIOSelectDynamic
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
final class TestbenchPort[T <: Data] private[tblib] (
  private val field: BundleField[T],
  private val testbench: Testbench[?]):
  /** Binds a wrapper value to this testbench input. */
  infix def :=(
    value: Value
  )(
    using Arena,
    Context,
    Block
  ): Unit = testbench.bind(field, value)

  /** Reads an output of the FIRRTL testbench in the simulation wrapper. */
  def value(
    using Arena
  ): Value = testbench.observe(field)

/** Typed access to the input and output ports of `I`. */
final class TestbenchIO[I <: HWInterface[?]] private[tblib] (private val testbench: Testbench[I]) extends Dynamic:
  private[tblib] def port[T <: Data](name:       String): TestbenchPort[T]         = testbench.port(name)
  private[tblib] def portOption[T <: Data](name: String): Option[TestbenchPort[T]] = testbench.portOption(name)

  transparent inline def selectDynamic(name: String): Any = ${ testbenchIOSelectDynamic[I]('this, 'name) }

/** Operations and typed IO bindings for one wrapper elaboration, supplied to `TestbenchGenerator.simulation`. */
trait Testbench[I <: HWInterface[?]]:
  def clock:        Value
  def fallingClock: Value
  def io:           TestbenchIO[I]

  /** Runs once at simulation startup. */
  def initial(
    body: Block ?=> Unit
  )(
    using Arena,
    Context,
    Block
  ): Unit

  /** Builds one ordered procedure on the rising edge of `clock`; use `fallingClock` for the falling edge. Inputs
    * assigned through `io` inside this procedure are registered and hold their values between triggers.
    */
  def onClock(
    clock:   Value,
    enabled: Option[Value] = None
  )(body:    Block ?=> Unit
  )(
    using Arena,
    Context,
    Block
  ): Unit

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

  /** Creates an independent DPI call on the falling edge. Use `dpiCallProcedural` inside `onClock` for ordering. */
  def dpiCall(
    function: DpiFunction,
    inputs:   Seq[Value] = Seq.empty,
    enabled:  Option[Value] = None
  )(
    using Arena,
    Context,
    Block
  ): DpiCallResult

  /** Calls a DPI function in the current procedure, in order, with immediately usable results. */
  def dpiCallProcedural(
    function: DpiFunction,
    inputs:   Seq[Value] = Seq.empty
  )(
    using Arena,
    Context,
    Block
  ): DpiCallResult

  /** Finishes immediately inside a procedure, or on the generated clock when called at module scope. */
  def finish(
    condition: Value,
    success:   Boolean = true
  )(
    using Arena,
    Context,
    Block
  ): Unit

  private[tblib] def port[T <: Data](name:       String): TestbenchPort[T]
  private[tblib] def portOption[T <: Data](name: String): Option[TestbenchPort[T]]
  private[tblib] def observe(
    field: BundleField[?]
  )(
    using Arena
  ):                                                      Value
  private[tblib] def connectOutputs(
    values: Seq[Value]
  )(
    using Arena,
    Context,
    Block
  ):                                                      Unit
  private[tblib] def bind(
    field: BundleField[?],
    value: Value
  )(
    using Arena,
    Context,
    Block
  ):                                                      Unit

  /** Resolves the instance inputs in HW port order, including the generated clock. */
  private[tblib] def inputValues: Seq[Value]

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
