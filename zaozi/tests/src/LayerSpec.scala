// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.testlib.*

import org.llvm.mlir.scalalib.capi.ir.{given_ContextApi, Block, Context, ContextApi}
import org.llvm.mlir.scalalib.capi.pass.{given_PassManagerApi, PassManager, PassManagerApi}
import utest.*

import java.lang.foreign.Arena
import scala.annotation.meta.param

case class LayerSpecParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[LayerSpecParameter] = upickle.default.macroRW

class LayerSpecLayers(parameter: LayerSpecParameter) extends LayerInterface(parameter):
  def layers = Seq(
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

class LayerSpecIO(parameter: LayerSpecParameter) extends HWBundle(parameter):
  val a0     = Flipped(UInt(parameter.width))
  val a0b0   = Flipped(UInt(parameter.width))
  val a0b0c0 = Flipped(UInt(parameter.width))
  val a0b1   = Flipped(UInt(parameter.width))

class LayerSpecProbe(parameter: LayerSpecParameter) extends DVBundle[LayerSpecParameter, LayerSpecLayers](parameter):
  val a0     = ProbeRead(UInt(parameter.width), layers("A0"))
  val a0b0   = ProbeRead(UInt(parameter.width), layers("A0")("A0B0"))
  val a0b0c0 = ProbeRead(UInt(parameter.width), layers("A0")("A0B0")("A0B0C0"))
  val a0b1   = ProbeRead(UInt(parameter.width), layers("A0")("A0B1"))

object LayerSpec extends TestSuite:
  val tests = Tests:
    test("Simple Layer"):
      @generator
      object SimpleLayer
          extends Generator[LayerSpecParameter, LayerSpecLayers, LayerSpecIO, LayerSpecProbe]
          with HasVerilogTest:
        def architecture(parameter: LayerSpecParameter) =
          val io    = summon[Interface[LayerSpecIO]]
          val probe = summon[Interface[LayerSpecProbe]]
          layer("A0"):
            // calculation to prevent optimization
            val a0p = Wire(UInt(parameter.width))
            a0p := (io.a0 + 1.U).asBits.tail(parameter.width).asUInt
            probe.a0 <== a0p
            layer("A0B0"):
              val a0b0p = Wire(UInt(parameter.width))
              a0b0p := (io.a0b0 - 1.U).asBits.tail(parameter.width).asUInt
              probe.a0b0 <== a0b0p
              layer("A0B0C0"):
                val a0b0c0p = Wire(UInt(parameter.width))
                a0b0c0p := (io.a0b0c0 * 3.U).asBits.tail(parameter.width).asUInt
                probe.a0b0c0 <== a0b0c0p
            layer("A0B1"):
              val a0b1p = Wire(UInt(parameter.width))
              a0b1p := (io.a0b1 << 1).asBits.tail(parameter.width).asUInt
              probe.a0b1 <== a0b1p

      val parameter  = LayerSpecParameter(32)
      val moduleName = SimpleLayer.moduleName(parameter)
      SimpleLayer.verilogTest(parameter)(
        s"bind ${moduleName} ${moduleName}_A0_A0B1 a0_a0B1",
        s"bind ${moduleName} ${moduleName}_A0_A0B0_A0B0C0 a0_a0B0_a0B0C0",
        s"bind ${moduleName} ${moduleName}_A0_A0B0 a0_a0B0",
        s"bind ${moduleName} ${moduleName}_A0 a0_0"
      )
