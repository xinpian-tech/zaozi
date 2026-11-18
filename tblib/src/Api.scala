// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.tblib

import me.jiuyang.stdlib.ClockGenerator
import me.jiuyang.tblib.macros.testbenchIOSelectDynamic
import me.jiuyang.zaozi.{
  DVInterface,
  DpiArg,
  DpiCallResult,
  DpiFunction,
  Generator,
  HWInterface,
  HWRecord,
  InstanceContext,
  LayerInterface,
  Parameter,
  SVApi,
  TypeImpl
}
import me.jiuyang.zaozi.default.{locate, given}
import me.jiuyang.zaozi.reftpe.{Interface, ProbeInterface}
import me.jiuyang.zaozi.valuetpe.{Clock as ClockType, Connectable, Data}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{OpenSubfieldApi, RefDefineApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Module, Type, Value, given}

import java.lang.foreign.Arena
import scala.language.dynamics

export org.llvm.circt.scalalib.capi.dialect.sim.DPIDirection

/** A DUT input awaiting a module-scope connection during simulation construction. */
final class TestbenchPort[T <: Data] private[tblib] (
  private[tblib] val name: String,
  private[tblib] val argument: Value):
  private[tblib] var connected: Boolean = false

  /** Binds a DUT input directly to a value at module scope. */
  infix def :=(
    value: Value
  )(
    using Arena,
    Context,
    Block,
    TestbenchGeneratorApi
  ): Unit =
    summon[TestbenchGeneratorApi].connect(this)(value)

/** Typed access to the stimulus input ports of `I`. */
final class TestbenchIO[I <: HWInterface[?]] private[tblib] (private val testbench: TestbenchContext[I])
    extends Dynamic:
  private[tblib] def port[T <: Data](name: String): TestbenchPort[T] =
    portOption[T](name).getOrElse(throw new IllegalArgumentException(s"$name is not a testbench port"))

  private[tblib] def portOption[T <: Data](name: String): Option[TestbenchPort[T]] =
    testbench.inputPorts.get(name).map(_.asInstanceOf[TestbenchPort[T]])

  transparent inline def selectDynamic(name: String): Any = ${ testbenchIOSelectDynamic[I]('this, 'name) }

/** State belonging to one testbench module during simulation construction. */
final class TestbenchContext[I <: HWInterface[?]] private[tblib] (
  val clock:        Value,
  val fallingClock: Value,
  private[tblib] val inputPorts: Map[String, TestbenchPort[Data]]):
  val io: TestbenchIO[I] = new TestbenchIO[I](this)

/** Internal FIRRTL input ports derived from the DUT, excluding its automatically connected clock. */
final class TestbenchInputs[PARAM <: Parameter] private[tblib] (
  parameter: PARAM,
  source:    HWInterface[PARAM])
    extends HWRecord(parameter):
  private var populated = false

  override def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type =
    if !populated then
      source.toMlirType
      source.elements
        .filter(field => field.isFlipped && field.name != "clock")
        .foreach: field =>
          Flipped(field.name, field.dataType)
      populated = true
    super.toMlirType

/** Instantiates the clock and DUT, and connects stimulus inputs by port name. DUT observation uses Probe. */
trait TestbenchGenerator[
  PARAM <: Parameter,
  L <: LayerInterface[PARAM],
  I <: HWInterface[PARAM],
  P <: DVInterface[PARAM, L]]
    extends Generator[PARAM, L, TestbenchInputs[PARAM], P]:
  def dut:   Generator[PARAM, L, I, P]
  def clock: ClockGenerator

  final override def interface(parameter: PARAM): TestbenchInputs[PARAM] =
    new TestbenchInputs(parameter, dut.interface(parameter))

  /** The forwarded DUT Probe interface in the current FIRRTL elaboration. */
  def probe(
    using current: ProbeInterface[P]
  ): ProbeInterface[P] = current

  protected final def risingClock(
    using TestbenchContext[I]
  ): Value = summon[TestbenchContext[I]].clock
  protected final def fallingClock(
    using TestbenchContext[I]
  ): Value = summon[TestbenchContext[I]].fallingClock

  protected final def initial(
    body: Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    TestbenchContext[I],
    TestbenchGeneratorApi
  ): Unit =
    summon[TestbenchGeneratorApi].initial(summon[TestbenchContext[I]])(body)

  protected final def onClock(
    clock:   Value,
    enabled: Option[Value] = None
  )(body:    Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    TestbenchContext[I],
    TestbenchGeneratorApi
  ): Unit =
    summon[TestbenchGeneratorApi].onClock(summon[TestbenchContext[I]])(clock, enabled)(body)

  protected final def dpiFunction(
    symbol:    String,
    cName:     Option[String],
    arguments: Seq[DpiArg]
  )(
    using Arena,
    Context,
    Block,
    TestbenchContext[I],
    TestbenchGeneratorApi
  ): DpiFunction =
    summon[TestbenchGeneratorApi].dpiFunction(summon[TestbenchContext[I]])(symbol, cName, arguments)

  protected final def dpiCall(
    function: DpiFunction,
    inputs:   Seq[Value] = Seq.empty,
    enabled:  Option[Value] = None
  )(
    using Arena,
    Context,
    Block,
    TestbenchContext[I],
    TestbenchGeneratorApi
  ): DpiCallResult =
    summon[TestbenchGeneratorApi].dpiCall(summon[TestbenchContext[I]])(function, inputs, enabled)

  protected final def dpiCallProcedural(
    function: DpiFunction,
    inputs:   Seq[Value] = Seq.empty
  )(
    using Arena,
    Context,
    Block,
    SVApi
  ): DpiCallResult =
    summon[SVApi].dpiCallProcedural(function, inputs)

  protected final def finish(
    condition: Value,
    success:   Boolean = true
  )(
    using Arena,
    Context,
    Block,
    TestbenchContext[I],
    TestbenchGeneratorApi
  ): Unit =
    summon[TestbenchGeneratorApi].finish(summon[TestbenchContext[I]])(condition, success)

  /** Instantiates the clock and DUT, connects stimulus inputs, and forwards the DUT Probe interface in FIRRTL.
    * Consumers access the forwarded interface through the testbench instance's `probe`.
    */
  override def architecture(parameter: PARAM): (
    Arena,
    Context,
    Block,
    Interface[TestbenchInputs[PARAM]],
    ProbeInterface[P],
    L,
    InstanceContext
  ) ?=> Unit =
    // clock
    val clock = this.clock.generator.instantiate(this.clock.parameter)

    // io
    val io  = summon[Interface[TestbenchInputs[PARAM]]]
    val dut = this.dut.instantiate(parameter)
    dut.io.field[ClockType]("clock") := clock.io.clock
    io.getType.elements.foreach: field =>
      dut.io.field[Connectable](field.name) :<= io.field[Connectable](field.name)

    // probe
    probe.getType.elements.indices.foreach: index =>
      val destination = summon[OpenSubfieldApi].op(probe.refer, index, locate)
      destination.operation.appendToBlock()
      val source      = summon[OpenSubfieldApi].op(dut.probe.refer, index, locate)
      source.operation.appendToBlock()
      summon[RefDefineApi].op(destination.result, source.result, locate).operation.appendToBlock()

  def simulation(parameter: PARAM): (
    Arena,
    Context,
    Block,
    TestbenchContext[I]
  ) ?=> Unit

/** Builds a unit testbench and exports its IR, DPI interface, or SystemVerilog. */
trait TestbenchGeneratorApi:
  extension [T <: Data](port: TestbenchPort[T])
    def connect(
      value: Value
    )(
      using Arena,
      Context,
      Block
    ): Unit

  extension [I <: HWInterface[?]](testbench: TestbenchContext[I])
    /** Runs once at simulation startup. */
    def initial(
      body: Block ?=> Unit
    )(
      using Arena,
      Context,
      Block
    ): Unit

    /** Builds one ordered procedure on the rising edge of `clock`; use `fallingClock` for the falling edge. */
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

    /** Finishes immediately inside a procedure, or on the generated clock when called at module scope. */
    def finish(
      condition: Value,
      success:   Boolean = true
    )(
      using Arena,
      Context,
      Block
    ): Unit

  extension [PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    dut: Generator[PARAM, L, I, P]
  )
    /** The DUT stimulus inputs in the current simulation construction. */
    def io(
      using TestbenchContext[I]
    ): TestbenchIO[I]

  extension [PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    generator: TestbenchGenerator[PARAM, L, I, P]
  )
    /** Handles `config` and `design`. Design writes complete testbench `.mlirbc`, DPI `.json`, and SystemVerilog `.sv`
      * files to `ZAOZI_OUTDIR` or the current working directory.
      */
    def mainImpl(
      args: Array[String]
    )(
      using upickle.default.ReadWriter[PARAM]
    ): Unit

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
