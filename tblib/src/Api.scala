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
  InstanceContext,
  LayerInterface,
  Parameter
}
import me.jiuyang.zaozi.default.{locate, given}
import me.jiuyang.zaozi.reftpe.{Interface, ProbeInterface}
import me.jiuyang.zaozi.valuetpe.{BundleField, Clock as ClockType, Connectable, Data}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{OpenSubfieldApi, RefDefineApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Module, Value, given}

import java.lang.foreign.Arena
import scala.language.dynamics

/** A typed stimulus input of one testbench. */
final class TestbenchPort[T <: Data] private[tblib] (
  private val field: BundleField[T],
  private val testbench: Testbench[?]):
  /** Binds a simulation value to this testbench input. */
  infix def :=(
    value: Value
  )(
    using Arena,
    Context,
    Block
  ): Unit = testbench.bind(field, value)

/** Typed access to the stimulus input ports of `I`. */
final class TestbenchIO[I <: HWInterface[?]] private[tblib] (private val testbench: Testbench[I]) extends Dynamic:
  private[tblib] def port[T <: Data](name:       String): TestbenchPort[T]         = testbench.port(name)
  private[tblib] def portOption[T <: Data](name: String): Option[TestbenchPort[T]] = testbench.portOption(name)

  transparent inline def selectDynamic(name: String): Any = ${ testbenchIOSelectDynamic[I]('this, 'name) }

/** Operations and typed IO bindings supplied to `TestbenchGenerator.simulation`. */
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
  private[tblib] def bind(
    field: BundleField[?],
    value: Value
  )(
    using Arena,
    Context,
    Block
  ):                                                      Unit

  /** Resolves the stimulus values in HW input port order. */
  private[tblib] def inputValues: Seq[Value]

/** Instantiates the clock and DUT, and connects stimulus inputs by port name. DUT observation uses Probe. */
trait TestbenchGenerator[
  PARAM <: Parameter,
  L <: LayerInterface[PARAM],
  I <: HWInterface[PARAM],
  P <: DVInterface[PARAM, L]]
    extends Generator[PARAM, L, I, P]:
  def dut: Generator[PARAM, L, ? <: HWInterface[PARAM], P]

  /** Instantiates the clock and DUT, connects stimulus inputs, and forwards the DUT Probe interface in FIRRTL.
    * Consumers access the forwarded interface through the testbench instance's `probe`.
    */
  override def architecture(parameter: PARAM): (
    Arena,
    Context,
    Block,
    Interface[I],
    ProbeInterface[P],
    L,
    InstanceContext
  ) ?=> Unit =
    // clock
    val clock = Clock.instantiate(ClockParameter(clockPeriodNs(parameter)))

    // io
    val io  = summon[Interface[I]]
    val dut = this.dut.instantiate(parameter)
    dut.io.field[ClockType]("clock") := clock.io.clock
    io.getType.elements.foreach: field =>
      dut.io.field[Connectable](field.name) :<= io.field[Connectable](field.name)

    // probe
    val probe = summon[ProbeInterface[P]]
    probe.getType.elements.indices.foreach: index =>
      val destination = summon[OpenSubfieldApi].op(probe.refer, index, locate)
      destination.operation.appendToBlock()
      val source      = summon[OpenSubfieldApi].op(dut.probe.refer, index, locate)
      source.operation.appendToBlock()
      summon[RefDefineApi].op(destination.result, source.result, locate).operation.appendToBlock()

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
    /** Links and lowers the FIRRTL inputs, then adds simulation behavior to the testbench. The caller owns the returned
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
    /** Returns the complete testbench as MLIR bytecode. */
    def toMlirBytecode(
      using Arena
    ): Array[Byte]

    /** Exports the DPI interface through CIRCT's C API and parses its JSON output. */
    def toDpiJson(
      using Arena
    ): ujson.Value

    /** Returns SystemVerilog text by lowering a copy, preserving the original module for further conversions. */
    def toVerilog(
      using Arena
    ): String
