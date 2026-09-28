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
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Value}

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

/** Typed access to the ports of `I` without exposing string-based lookup to the testbench. */
final class TestbenchIO[I <: HWInterface[?]] private[utlib] (private val testbench: Testbench[I]) extends Dynamic:
  private[utlib] def port[T <: Data](name:       String): TestbenchPort[T]         = testbench.port(name)
  private[utlib] def portOption[T <: Data](name: String): Option[TestbenchPort[T]] = testbench.portOption(name)

  transparent inline def selectDynamic(name: String): Any = ${ testbenchIOSelectDynamic[I]('this, 'name) }

/** Operations and typed IO bindings available while building one simulation wrapper. */
trait Testbench[I <: HWInterface[?]]:
  def io:           TestbenchIO[I]
  def clock:        Value
  def fallingClock: Value

  /** Declares a DPI function at the builtin module scope. */
  def dpiFunction(
    symbol:    String,
    cName:     Option[String],
    arguments: Seq[DpiArg]
  )(
    using Arena,
    Context
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
  ):                                                      Unit
  private[utlib] def port[T <: Data](name:       String): TestbenchPort[T]
  private[utlib] def portOption[T <: Data](name: String): Option[TestbenchPort[T]]
  private[utlib] def bind(
    port:  BundleField[?],
    value: Value
  )(
    using Arena
  ):                                                      Unit

/** Simulation behavior attached to a FIRRTL testbench generator. */
trait UT[PARAM <: Parameter, I <: HWInterface[PARAM]]:
  def clockPeriodNs(parameter: PARAM): Long
  def simulation(parameter:    PARAM): (
    Arena,
    Context,
    Block,
    Testbench[I]
  ) ?=> Unit

/** Emits a FIRRTL testbench together with its HW/SV simulation wrapper. */
trait UTApi:
  extension [PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    ut: Generator[PARAM, L, I, P] & UT[PARAM, I]
  ) def emit(parameter: PARAM): Unit
