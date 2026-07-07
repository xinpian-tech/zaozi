// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 <liu@jiuyang.me>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class GCD %s --
// RUN: rm -rf %t && mkdir -p %t && cd %t
// RUN: %{test} config %t/config.json --width 32 --use-async-reset false
// RUN: %{test} design %t/config.json
// RUN: firld %t/*.mlirbc --base-circuit GCD_35bf2066 --no-mangle | firtool --format=mlir | FileCheck %s --check-prefixes=GCD,SUB
// RUN: rm -rf %t

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.magic.macros.generator
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Module as MlirModule}

import java.lang.foreign.Arena
import mainargs.TokensReader

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

// GCD-DAG:      module GCD_{{.*}}(
// GCD-DAG-NEXT:   input         clock,
// GCD-DAG-NEXT:                 reset,
// GCD-DAG-NEXT:   output        input_ready,
// GCD-DAG-NEXT:   input         input_valid,
// GCD-DAG-NEXT:   input  [31:0] input_bits_x,
// GCD-DAG-NEXT:                 input_bits_y,
// GCD-DAG-NEXT:   output        output_valid,
// GCD-DAG-NEXT:   output [31:0] output_bits_z
// GCD-DAG-NEXT: );
class GCDIO(parameter: GCDParameter) extends HWBundle(parameter):
  val clock:  BundleField[Clock]                 = Flipped(Clock())
  val reset:  BundleField[Reset]                 = Flipped(Reset())
  val input:  BundleField[DecoupledIO[GCDInput]] = Flipped(Decoupled(new GCDInput(parameter)))
  val output: BundleField[ValidIO[GCDOutput]]    = Aligned(Valid(GCDOutput(parameter)))

class GCDProbe(parameter: GCDParameter) extends DVBundle[GCDParameter, GCDLayers](parameter)

case class SubtractorParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[SubtractorParameter] = upickle.default.macroRW

class SubtractorLayers(parameter: SubtractorParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class SubtractorIO(
  parameter: SubtractorParameter)
    extends HWBundle(parameter):
  val a = Flipped(UInt(parameter.width))
  val b = Flipped(UInt(parameter.width))
  val z = Aligned(UInt(parameter.width))

class SubtractorProbe(parameter: SubtractorParameter) extends DVBundle[SubtractorParameter, SubtractorLayers](parameter)

// SUB-DAG:      module Subtractor_{{.*}}(
// SUB-DAG-NEXT:   input [31:0] a,
// SUB-DAG-NEXT:   b,
// SUB-DAG-NEXT:   output [31:0] z
// SUB-DAG-NEXT: );
@generator
object Subtractor extends Generator[SubtractorParameter, SubtractorLayers, SubtractorIO, SubtractorProbe]:
  def architecture(parameter: SubtractorParameter) =
    val io = summon[Interface[SubtractorIO]]
    io.z := (io.a - io.b).asBits.tail(parameter.width).asUInt

case class GenXParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[GenXParameter] = upickle.default.macroRW

class GenXLayers(parameter: GenXParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class GenXIO(parameter: GenXParameter) extends HWBundle(parameter):
  val x          = Flipped(UInt(parameter.width))
  val y          = Flipped(UInt(parameter.width))
  val inputFire  = Flipped(Bool())
  val inputValue = Flipped(UInt(parameter.width))
  val xGreaterY  = Flipped(Bool())
  val nextX      = Aligned(UInt(parameter.width))

class GenXProbe(parameter: GenXParameter) extends DVBundle[GenXParameter, GenXLayers](parameter)

@generator
object GenX extends Generator[GenXParameter, GenXLayers, GenXIO, GenXProbe]:
  def architecture(parameter: GenXParameter) =
    val io  = summon[Interface[GenXIO]]
    val sub = Subtractor.instantiate(SubtractorParameter(parameter.width))
    sub.io.a := io.x
    sub.io.b := io.y

    io.nextX := io.inputFire ? (
      io.inputValue,
      io.xGreaterY ? (
        sub.io.z,
        io.x
      )
    )

case class GenYParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[GenYParameter] = upickle.default.macroRW

class GenYLayers(parameter: GenYParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class GenYIO(parameter: GenYParameter) extends HWBundle(parameter):
  val x          = Flipped(UInt(parameter.width))
  val y          = Flipped(UInt(parameter.width))
  val inputFire  = Flipped(Bool())
  val inputValue = Flipped(UInt(parameter.width))
  val xGreaterY  = Flipped(Bool())
  val nextY      = Aligned(UInt(parameter.width))

class GenYProbe(parameter: GenYParameter) extends DVBundle[GenYParameter, GenYLayers](parameter)

@generator
object GenY extends Generator[GenYParameter, GenYLayers, GenYIO, GenYProbe]:
  def architecture(parameter: GenYParameter) =
    val io  = summon[Interface[GenYIO]]
    val sub = Subtractor.instantiate(SubtractorParameter(parameter.width))
    sub.io.a := io.y
    sub.io.b := io.x

    io.nextY := io.inputFire ? (
      io.inputValue,
      io.xGreaterY ? (
        io.y,
        sub.io.z
      )
    )

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

    // GCD-DAG:      GenX_{{.*}} genX (
    // GCD-DAG-NEXT:   .x (x),
    // GCD-DAG-NEXT:   .y (y),
    // GCD-DAG-NEXT:   .inputFire (_input_fire),
    // GCD-DAG-NEXT:   .inputValue (input_bits_x),
    // GCD-DAG-NEXT:   .xGreaterY (_genX_xGreaterY),
    // GCD-DAG-NEXT:   .nextX (_genX_nextX)
    // GCD-DAG-NEXT: );
    val genX = GenX.instantiate(GenXParameter(parameter.width))
    genX.io.x          := x
    genX.io.y          := y
    genX.io.inputFire  := io.input.fire
    genX.io.inputValue := io.input.bits.x
    genX.io.xGreaterY  := x > y

    // GCD-DAG:      GenY_{{.*}} genY (
    // GCD-DAG-NEXT:   .x (x),
    // GCD-DAG-NEXT:   .y (y),
    // GCD-DAG-NEXT:   .inputFire (_input_fire),
    // GCD-DAG-NEXT:   .inputValue (input_bits_y),
    // GCD-DAG-NEXT:   .xGreaterY (_genY_xGreaterY),
    // GCD-DAG-NEXT:   .nextY (_genY_nextY)
    // GCD-DAG-NEXT: );
    val genY = GenY.instantiate(GenYParameter(parameter.width))
    genY.io.x          := x
    genY.io.y          := y
    genY.io.inputFire  := io.input.fire
    genY.io.inputValue := io.input.bits.y
    genY.io.xGreaterY  := x > y

    x := genX.io.nextX
    y := genY.io.nextY

    startupFlag := io.input.fire ? (
      true.B,
      startupFlag
    )
