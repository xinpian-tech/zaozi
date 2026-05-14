// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.testlib.*
import org.llvm.circt.scalalib.capi.dialect.firrtl.DialectApi as FirrtlDialectApi
import org.llvm.circt.scalalib.capi.dialect.firrtl.given_DialectApi
import org.llvm.mlir.scalalib.capi.ir.{given_ContextApi, Context, ContextApi}

import java.lang.foreign.Arena
import utest.*
import me.jiuyang.zaozi.magic.macros.generator

case class WidthSpecParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[WidthSpecParameter] = upickle.default.macroRW

class WidthSpecLayers(parameter: WidthSpecParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class WidthSpecIO(parameter: WidthSpecParameter) extends HWBundle(parameter):
  val i = Flipped(UInt(parameter.width))
  val o = Aligned(UInt(parameter.width))

class WidthSpecProbe(parameter: WidthSpecParameter) extends DVBundle[WidthSpecParameter, WidthSpecLayers](parameter)

class WidthBundle extends Bundle:
  val x = Aligned(UInt(4))
  val y = Aligned(SInt(8))

object WidthSpec extends TestSuite:
  private def withContext(body: (Arena, Context) ?=> Unit): Unit =
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      summon[FirrtlDialectApi].loadDialect
      body
      summon[Context].destroy()
    finally arena.close()

  val tests = Tests:
    test("Data"):
      test("UInt"):
        withContext:
          assert(UInt(8).width == 8)
          assert(UInt(1).width == 1)
          assert(UInt(32).width == 32)

      test("SInt"):
        withContext:
          assert(SInt(8).width == 8)
          assert(SInt(16).width == 16)

      test("Bits"):
        withContext:
          assert(Bits(8).width == 8)

      test("Bool"):
        withContext:
          assert(Bool().width == 1)

      test("Vec"):
        withContext:
          assert(Vec(4, UInt(8)).width == 32)
          assert(Vec(2, Bits(3)).width == 6)

      test("Bundle"):
        withContext:
          val b = new Bundle:
            val x = Aligned(UInt(4))
            val y = Aligned(UInt(8))
          assert(b.width == 12)

      test("Nested Bundle"):
        withContext:
          val inner = new Bundle:
            val a = Aligned(UInt(4))
            val b = Aligned(UInt(4))
          val outer = new Bundle:
            val x = Aligned(inner)
            val y = Aligned(UInt(8))
          assert(outer.width == 16)

      test("Vec of Bundle"):
        withContext:
          val b = new Bundle:
            val a = Aligned(UInt(4))
            val b = Aligned(UInt(4))
          assert(Vec(3, b).width == 24)

    test("Referable"):
      test("Wire"):
        @generator
        object WireWidth
            extends Generator[WidthSpecParameter, WidthSpecLayers, WidthSpecIO, WidthSpecProbe]
            with HasMlirTest:
          def architecture(parameter: WidthSpecParameter) =
            val io   = summon[Interface[WidthSpecIO]]
            val wire = Wire(UInt(parameter.width))
            assert(wire.width == parameter.width)
            io.o := wire
            wire := io.i
        WireWidth.mlirTest(WidthSpecParameter(8))(_ => true)

      test("Bundle Wire"):
        @generator
        object BundleWireWidth
            extends Generator[WidthSpecParameter, WidthSpecLayers, WidthSpecIO, WidthSpecProbe]
            with HasMlirTest:
          def architecture(parameter: WidthSpecParameter) =
            val io         = summon[Interface[WidthSpecIO]]
            val bundleWire = Wire(new WidthBundle)
            assert(bundleWire.width == 12)
            io.o := bundleWire.asBits.bits(parameter.width - 1, 0).asUInt
            io.i.dontCare()
        BundleWireWidth.mlirTest(WidthSpecParameter(8))(_ => true)

      test("Vec Wire"):
        @generator
        object VecWireWidth
            extends Generator[WidthSpecParameter, WidthSpecLayers, WidthSpecIO, WidthSpecProbe]
            with HasMlirTest:
          def architecture(parameter: WidthSpecParameter) =
            val io      = summon[Interface[WidthSpecIO]]
            val vecWire = Wire(Vec(4, UInt(parameter.width)))
            assert(vecWire.width == 4 * parameter.width)
            io.o := vecWire.asBits.bits(parameter.width - 1, 0).asUInt
            io.i.dontCare()
        VecWireWidth.mlirTest(WidthSpecParameter(8))(_ => true)
