// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 <liu@jiuyang.me>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class Outer %s --
// RUN: rm -rf %t && mkdir -p %t && cd %t
// RUN: %{test} config %t/config.json --width 32
// RUN: %{test} design %t/config.json
// RUN: firld %t/*.mlirbc --base-circuit Outer_e4dbbef1 | firtool -format=mlir | FileCheck %s
// RUN: rm -rf %t

// XFAIL: *
// https://github.com/llvm/circt/issues/9817

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import java.lang.foreign.Arena

val fooLayers = Seq(
  Layer(
    "A0",
    Seq(
      Layer(
        "A0B0",
        Seq(
          Layer(
            "A0B0C0"
          ),
          Layer(
            "A0B0C1"
          )
        )
      ),
      Layer("A0B1"),
      Layer("A0B2")
    )
  ),
  Layer("A1")
)

case class InnerParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[InnerParameter] = upickle.default.macroRW

class InnerLayers(parameter: InnerParameter) extends LayerInterface(parameter):
  def layers = fooLayers

class InnerIO(parameter: InnerParameter) extends HWBundle(parameter):
  val a0     = Flipped(UInt(parameter.width))
  val a0b0   = Flipped(UInt(parameter.width))
  val a0b0c0 = Flipped(UInt(parameter.width))
  val a0b1   = Flipped(UInt(parameter.width))

class InnerProbe(parameter: InnerParameter) extends DVBundle[InnerParameter, InnerLayers](parameter):
  // CHECK-DAG: `define ref_Inner_7b2bb635_a0 a0_0.a0p_probe
  val a0     = ProbeRead(UInt(parameter.width), layers("A0"))
  // CHECK-DAG: `define ref_Inner_7b2bb635_a0b0 a0_a0B0.a0b0p_probe
  val a0b0   = ProbeRead(UInt(parameter.width), layers("A0")("A0B0"))
  // CHECK-DAG: `define ref_Inner_7b2bb635_a0b0c0 a0_a0B0_a0B0C0.a0b0c0p_probe
  val a0b0c0 = ProbeRead(UInt(parameter.width), layers("A0")("A0B0")("A0B0C0"))
  // CHECK-DAG: `define ref_Inner_7b2bb635_a0b1 a0_a0B1.a0b1p_probe
  val a0b1   = ProbeRead(UInt(parameter.width), layers("A0")("A0B1"))

@generator
object Inner extends Generator[InnerParameter, InnerLayers, InnerIO, InnerProbe]:
  def architecture(parameter: InnerParameter) =
    val io    = summon[Interface[InnerIO]]
    val probe = summon[Interface[InnerProbe]]
    layer("A0"):
      // CHECK-DAG: bind Inner_7b2bb635 Inner_7b2bb635_A0 a0_0 ();
      val a0p = Wire(UInt(parameter.width))
      a0p := (io.a0 + 1.U).asBits.tail(parameter.width).asUInt
      probe.a0 <== a0p
      layer("A0B0"):
        // CHECK-DAG: bind Inner_7b2bb635 Inner_7b2bb635_A0_A0B0 a0_a0B0 ();
        val a0b0p = Wire(UInt(parameter.width))
        a0b0p := (io.a0b0 - 1.U).asBits.tail(parameter.width).asUInt
        probe.a0b0 <== a0b0p
        layer("A0B0C0"):
          // CHECK-DAG: bind Inner_7b2bb635 Inner_7b2bb635_A0_A0B0_A0B0C0 a0_a0B0_a0B0C0 ();
          val a0b0c0p = Wire(UInt(parameter.width))
          a0b0c0p := (io.a0b0c0 * 3.U).asBits.tail(parameter.width).asUInt
          probe.a0b0c0 <== a0b0c0p
      layer("A0B1"):
        // CHECK-DAG: bind Inner_7b2bb635 Inner_7b2bb635_A0_A0B1 a0_a0B1 ();
        val a0b1p = Wire(UInt(parameter.width))
        a0b1p := (io.a0b1 << 1).asBits.tail(parameter.width).asUInt
        probe.a0b1 <== a0b1p

case class OuterParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[OuterParameter] = upickle.default.macroRW

class OuterLayers(parameter: OuterParameter) extends LayerInterface(parameter):
  def layers = fooLayers

class OuterIO(parameter: OuterParameter) extends HWBundle(parameter):
  val a0     = Flipped(UInt(parameter.width))
  val a0b0   = Flipped(UInt(parameter.width))
  val a0b0c0 = Flipped(UInt(parameter.width))
  val a0b1   = Flipped(UInt(parameter.width))

class OuterProbe(parameter: OuterParameter) extends DVBundle[OuterParameter, OuterLayers](parameter):
  // CHECK-DAG: `define ref_Outer_e4dbbef1_a0 a0_0.a0_val_probe
  val a0     = ProbeRead(UInt(parameter.width), layers("A0"))
  // CHECK-DAG: `define ref_Outer_e4dbbef1_a0b0 a0_a0B0.a0b0_val_probe
  val a0b0   = ProbeRead(UInt(parameter.width), layers("A0")("A0B0"))
  // CHECK-DAG: `define ref_Outer_e4dbbef1_a0b0c0 a0_a0B0_a0B0C0.a0b0c0_val_probe
  val a0b0c0 = ProbeRead(UInt(parameter.width), layers("A0")("A0B0")("A0B0C0"))
  // CHECK-DAG: `define ref_Outer_e4dbbef1_a0b1 a0_a0B1.a0b1_val_probe
  val a0b1   = ProbeRead(UInt(parameter.width), layers("A0")("A0B1"))

@generator
object Outer extends Generator[OuterParameter, OuterLayers, OuterIO, OuterProbe]:
  def architecture(parameter: OuterParameter) =
    val io    = summon[Interface[OuterIO]]
    val probe = summon[Interface[OuterProbe]]

    val inner = Inner.instantiate(InnerParameter(parameter.width))
    inner.io.a0     := io.a0
    inner.io.a0b0   := io.a0b0
    inner.io.a0b0c0 := io.a0b0c0
    inner.io.a0b1   := io.a0b1

    layer("A0"):
      // CHECK-DAG: bind Outer_e4dbbef1 Outer_e4dbbef1_A0 a0_0 ();
      val a0_val = Wire(UInt(parameter.width))
      a0_val <== inner.probe.a0
      probe.a0 <== a0_val
      layer("A0B0"):
        // CHECK-DAG: bind Outer_e4dbbef1 Outer_e4dbbef1_A0_A0B0 a0_a0B0 ();
        val a0b0_val = Wire(UInt(parameter.width))
        a0b0_val <== inner.probe.a0b0
        probe.a0b0 <== a0b0_val
        layer("A0B0C0"):
          // CHECK-DAG: bind Outer_e4dbbef1 Outer_e4dbbef1_A0_A0B0_A0B0C0 a0_a0B0_a0B0C0 ();
          val a0b0c0_val = Wire(UInt(parameter.width))
          a0b0c0_val <== inner.probe.a0b0c0
          probe.a0b0c0 <== a0b0c0_val
      layer("A0B1"):
        // CHECK-DAG: bind Outer_e4dbbef1 Outer_e4dbbef1_A0_A0B1 a0_a0B1 ();
        val a0b1_val = Wire(UInt(parameter.width))
        a0b1_val <== inner.probe.a0b1
        probe.a0b1 <== a0b1_val
