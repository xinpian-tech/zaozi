// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.testlib.*

import utest.*

case class ContractSpecParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[ContractSpecParameter] = upickle.default.macroRW

class ContractSpecLayers(parameter: ContractSpecParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ContractSpecIO(parameter: ContractSpecParameter) extends HWBundle(parameter):
  val p = Flipped(UInt(parameter.width))
  val q = Flipped(UInt(parameter.width))
  val r = Flipped(UInt(parameter.width))

class ContractSpecProbe(parameter: ContractSpecParameter)
    extends DVBundle[ContractSpecParameter, ContractSpecLayers](parameter)

object ContractSpec extends TestSuite:
  val parameter = ContractSpecParameter(8)

  val tests = Tests:
    test("no arguments"):
      @generator
      object NoArguments
          extends Generator[
            ContractSpecParameter,
            ContractSpecLayers,
            ContractSpecIO,
            ContractSpecProbe
          ]
          with HasMlirTest
          with HasVerilogTest:
        def architecture(parameter: ContractSpecParameter) =
          val io = summon[Interface[ContractSpecIO]]
          val p  = io.p

          Contract {
            Require((p >= 1.U).I)
            Ensure((p + p >= 2.U).I)
          }

      NoArguments.mlirTest(parameter)(
        "firrtl.contract {",
        "  %c1_ui1 = firrtl.constant 1 : !firrtl.uint<1>",
        "  %4 = firrtl.geq %3, %c1_ui1 : (!firrtl.uint<8>, !firrtl.uint<1>) -> !firrtl.uint<1>",
        "  %_GEN_0 = firrtl.node interesting_name %4 : !firrtl.uint<1>",
        "  %5 = firrtl.add %3, %3 : (!firrtl.uint<8>, !firrtl.uint<8>) -> !firrtl.uint<9>",
        "  %_GEN_1 = firrtl.node interesting_name %5 : !firrtl.uint<9>",
        "  %c2_ui2 = firrtl.constant 2 : !firrtl.uint<2>",
        "  %6 = firrtl.geq %_GEN_1, %c2_ui2 : (!firrtl.uint<9>, !firrtl.uint<2>) -> !firrtl.uint<1>",
        "  %_GEN_2 = firrtl.node interesting_name %6 : !firrtl.uint<1>",
        "  firrtl.int.verif.require %_GEN_0 : !firrtl.uint<1>",
        "  firrtl.int.verif.ensure %_GEN_2 : !firrtl.uint<1>",
        "}"
      )

    test("single argument"):
      @generator
      object SingleArgument
          extends Generator[
            ContractSpecParameter,
            ContractSpecLayers,
            ContractSpecIO,
            ContractSpecProbe
          ]
          with HasVerilogTest:
        def architecture(parameter: ContractSpecParameter) =
          val io = summon[Interface[ContractSpecIO]]
          val p  = io.p

          val out = Contract((p << 3) + p) { b =>
            Ensure((b === p * 9.U).I)
          }

      SingleArgument.verilogTest(parameter)(
        // ensure b = p * 9.U
        "assume property (_GEN == {4'h0, p} * 12'h9);",
        // formal contract
        "assert property ({1'h0, _GEN, 3'h0} + _GEN_0 == _GEN_0 * 12'h9);"
      )

    test("multiple arguments"):
      @generator
      object MultipleArguments
          extends Generator[
            ContractSpecParameter,
            ContractSpecLayers,
            ContractSpecIO,
            ContractSpecProbe
          ]
          with HasVerilogTest:
        def architecture(parameter: ContractSpecParameter) =
          val io = summon[Interface[ContractSpecIO]]
          val p  = io.p
          val q  = io.q
          val r  = io.r
          val pb = p.asBits
          val qb = q.asBits
          val rb = r.asBits
          val s  = (pb ^ qb ^ rb).asUInt
          val c  = ((pb & qb | (pb ^ qb) & rb).asUInt) << 1

          val (u, v) = Contract((c, s)) { case (u, v) =>
            Ensure((u + v === p + q + r).I)
          }

      MultipleArguments.verilogTest(parameter)(
        // ensure u + v = p + q + r
        "assume property ({1'h0, _GEN} + {2'h0, _GEN_0} == {1'h0, {1'h0, p} + {1'h0, q}}",
        "                 + {2'h0, r});",
        // formal contract
        "assert property ({1'h0, _GEN & _GEN_0 | s & _GEN_1, 1'h0}",
        "                 + {2'h0, s ^ _GEN_1} == {1'h0, {1'h0, _GEN} + {1'h0, _GEN_0}}",
        "                 + {2'h0, _GEN_1});"
      )
