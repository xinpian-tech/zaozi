// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.benchmark

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.magic.validateCircuit
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_DialectApi, DialectApi as FirrtlDialectApi}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{given_CircuitApi, given_ModuleApi, Circuit, CircuitApi}
import org.llvm.mlir.scalalib.capi.ir.{
  given_ContextApi,
  given_LocationApi,
  given_ModuleApi,
  given_NamedAttributeApi,
  given_RegionApi,
  given_TypeApi,
  given_ValueApi,
  Block,
  Context,
  ContextApi,
  LocationApi,
  Module as MlirModule,
  ModuleApi as MlirModuleApi,
  NamedAttributeApi
}

import java.lang.foreign.Arena
import mainargs.TokensReader
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

trait HasFire[T <: Bundle, R <: Referable[T]]:
  extension (ref: R)
    def fire(
      using ctx: Context,
      file:      sourcecode.File,
      line:      sourcecode.Line,
      valName:   sourcecode.Name.Machine,
      instCtx:   InstanceContext
    )(
      using Arena,
      Block
    ): Node[Bool]

object Decoupled:
  def apply[T <: Bundle](bits: T) = new DecoupledIO[T](bits)

class DecoupledIO[T <: Bundle](_bits: T) extends Bundle:
  val ready: BundleField[Bool] = Flipped(Bool())
  val valid: BundleField[Bool] = Aligned(Bool())
  val bits:  BundleField[T]    = Aligned(_bits)

given [E <: Bundle, T <: DecoupledIO[E], R <: Referable[T]]: HasFire[T, R] with
  extension (ref: R)
    def fire(
      using ctx: Context,
      file:      sourcecode.File,
      line:      sourcecode.Line,
      valName:   sourcecode.Name.Machine,
      instCtx:   InstanceContext
    )(
      using Arena,
      Block
    ): Node[Bool] = ref.valid & ref.ready

object Valid:
  def apply[T <: Data](bits: T): ValidIO[T] = new ValidIO[T](bits)

class ValidIO[T <: Data](_bits: T) extends Bundle:
  val valid: BundleField[Bool] = Aligned(Bool())
  val bits:  BundleField[T]    = Aligned(_bits)

case class GCDParameter(width: Int, useAsyncReset: Boolean) extends Parameter
given upickle.default.ReadWriter[GCDParameter] = upickle.default.macroRW

class GCDLayers(parameter: GCDParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class GCDInput(parameter: GCDParameter) extends Bundle:
  val x: BundleField[UInt] = Aligned(UInt(parameter.width))
  val y: BundleField[UInt] = Aligned(UInt(parameter.width))

class GCDOutput(parameter: GCDParameter) extends Bundle:
  val z: BundleField[UInt] = Aligned(UInt(parameter.width))

class GCDIO(parameter: GCDParameter) extends HWBundle(parameter):
  val clock:  BundleField[Clock]                 = Flipped(Clock())
  val reset:  BundleField[Reset]                 = Flipped(Reset())
  val input:  BundleField[DecoupledIO[GCDInput]] = Flipped(Decoupled(new GCDInput(parameter)))
  val output: BundleField[ValidIO[GCDOutput]]    = Aligned(Valid(GCDOutput(parameter)))

class GCDProbe(parameter: GCDParameter) extends DVBundle[GCDParameter, GCDLayers](parameter)

@generator
object GCD extends Generator[GCDParameter, GCDLayers, GCDIO, GCDProbe]:
  def architecture(parameter: GCDParameter) =
    val io            = summon[Interface[GCDIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope =
      if parameter.useAsyncReset then ResetScope.asyncActiveHigh(io.reset)
      else ResetScope.syncActiveHigh(io.reset)

    val x           = Reg(UInt(parameter.width))
    val y           = RegInit(0.U(parameter.width))
    val startupFlag = RegInit(false.B)
    val busy        = y =/= 0.U

    io.input.ready   := !busy
    io.output.bits.z := x
    io.output.valid  := !busy & startupFlag

    x := io.input.fire ? (
      io.input.bits.x,
      (x > y) ? (
        (x - y).asBits.tail(parameter.width).asUInt,
        x
      )
    )

    y := io.input.fire ? (
      io.input.bits.y,
      (x > y) ? (
        y,
        (y - x).asBits.tail(parameter.width).asUInt
      )
    )

    startupFlag := io.input.fire ? (
      true.B,
      startupFlag
    )

// Run with:
// mill zaozi.benchmark.runJmh
class ZaoziBenchmark {

  // This is just an example, copy-paste and modify as appropriate
  // Typically 10 iterations for both warmup and measurement is better
  @Benchmark
  @Warmup(iterations = 3)
  @Measurement(iterations = 3)
  @Fork(value = 1)
  @Threads(value = 1)
  def ZaoziGCDTest(blackHole: Blackhole): Unit =
    val parameter = GCDParameter(32, false)
    val arena     = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      summon[FirrtlDialectApi].loadDialect

      given MlirModule = summon[MlirModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
      given Circuit    = summon[CircuitApi].op(GCD.moduleName(parameter))
      summon[Circuit].appendToModule()
      GCD.module(parameter).appendToCircuit()
      validateCircuit()

      summon[Context].destroy()
    finally arena.close()
}
