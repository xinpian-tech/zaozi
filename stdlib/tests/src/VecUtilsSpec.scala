// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Yuhang Zeng <unlsycn@unlsycn.com>
package me.jiuyang.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.testlib.*

import java.lang.foreign.Arena
import utest.*

case class VecUtilsSpecParameter(width: Int, vecCount: Int) extends Parameter
given upickle.default.ReadWriter[VecUtilsSpecParameter] = upickle.default.macroRW

class VecUtilsSpecLayers(parameter: VecUtilsSpecParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class VecUtilsSpecIO(parameter: VecUtilsSpecParameter) extends HWBundle(parameter):
  val a    = Flipped(Vec(parameter.vecCount, Bits(parameter.width)))
  val b    = Aligned(Vec(parameter.vecCount, Bits(parameter.width)))
  val cond = Flipped(Bool())
  val out  = Aligned(Bits(parameter.width * parameter.vecCount))

class VecUtilsSpecProbe(parameter: VecUtilsSpecParameter)
    extends DVBundle[VecUtilsSpecParameter, VecUtilsSpecLayers](parameter)

object VecUtilsSpec extends TestSuite:
  val tests = Tests:
    test("toSeq"):
      test("zipWithIndex foreach"):
        @generator
        object ZipWithIndex
            extends Generator[VecUtilsSpecParameter, VecUtilsSpecLayers, VecUtilsSpecIO, VecUtilsSpecProbe]
            with HasVerilogTest:
          def architecture(parameter: VecUtilsSpecParameter) =
            val io    = summon[Interface[VecUtilsSpecIO]]
            val elems = io.a.toSeq
            io.out.dontCare()
            elems.zipWithIndex.foreach: (e, i) =>
              io.b(i) := e
        ZipWithIndex.verilogTest(VecUtilsSpecParameter(8, 4))(
          "assign b_0 = a_0",
          "assign b_1 = a_1",
          "assign b_2 = a_2",
          "assign b_3 = a_3"
        )

      test("reverse assignment"):
        @generator
        object ReverseAssign
            extends Generator[VecUtilsSpecParameter, VecUtilsSpecLayers, VecUtilsSpecIO, VecUtilsSpecProbe]
            with HasVerilogTest:
          def architecture(parameter: VecUtilsSpecParameter) =
            val io    = summon[Interface[VecUtilsSpecIO]]
            val elems = io.a.toSeq
            io.out.dontCare()
            elems.reverse.zipWithIndex.foreach: (e, i) =>
              io.b(i) := e
        ReverseAssign.verilogTest(VecUtilsSpecParameter(8, 4))(
          "assign b_0 = a_3",
          "assign b_1 = a_2",
          "assign b_2 = a_1",
          "assign b_3 = a_0"
        )

      test("access inside when"):
        @generator
        object AccessInsideWhen
            extends Generator[VecUtilsSpecParameter, VecUtilsSpecLayers, VecUtilsSpecIO, VecUtilsSpecProbe]
            with HasMlirTest:
          def architecture(parameter: VecUtilsSpecParameter) =
            val io    = summon[Interface[VecUtilsSpecIO]]
            val elems = io.a.toSeq
            io.b.dontCare()
            io.out.dontCare()
            // toSeq captures the outer Block; SubindexOps are emitted there.
            // Inside the when body: io.b(0) uses index=0, elems(1) uses index=1.
            when(io.cond):
              io.b(0) := elems(1)
        AccessInsideWhen.mlirTest(VecUtilsSpecParameter(8, 2)): out =>
          val lines        = out.split("\n")
          val whenOpenIdx  = lines.indexWhere(_.contains("\"firrtl.when\""))
          val whenCloseIdx = lines.indexWhere(_.contains("}) : (!firrtl.uint<1>) -> ()"))
          // index=0 (io.b[0]) should be inside the when region
          val idx0Line     = lines.indexWhere(l => l.contains("\"firrtl.subindex\"") && l.contains("index = 0"))
          // index=1 (io.a[1] from elems(1)) should be outside (after) the when region
          val idx1Line     = lines.indexWhere(l => l.contains("\"firrtl.subindex\"") && l.contains("index = 1"))
          whenOpenIdx >= 0 && whenCloseIdx >= 0
          && idx0Line > whenOpenIdx && idx0Line < whenCloseIdx
          && idx1Line > whenCloseIdx

    test("toVec"):
      test("reverse toVec"):
        @generator
        object ReverseToVec
            extends Generator[VecUtilsSpecParameter, VecUtilsSpecLayers, VecUtilsSpecIO, VecUtilsSpecProbe]
            with HasVerilogTest:
          def architecture(parameter: VecUtilsSpecParameter) =
            val io = summon[Interface[VecUtilsSpecIO]]
            io.out.dontCare()
            io.b :<= io.a.toSeq.reverse.toVec
        ReverseToVec.verilogTest(VecUtilsSpecParameter(8, 4))(
          "assign b_0 = a_3",
          "assign b_1 = a_2",
          "assign b_2 = a_1",
          "assign b_3 = a_0"
        )
