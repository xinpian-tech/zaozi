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
  val a = Flipped(UInt(parameter.width))
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
          val a  = io.a

          Contract {
            Require(a >= 1.U)
            Ensure(a + a >= 2.U)
          }

      NoArguments.mlirTest(parameter)(
        "%4 = firrtl.subfield %io[a]",
        "%c1_ui1 = firrtl.constant 1",
        // a >= 1
        "%5 = firrtl.geq %4, %c1_ui1",
        "%_GEN_0 = firrtl.node interesting_name %5",
        // a + a
        "%6 = firrtl.add %4, %4",
        "%_GEN_1 = firrtl.node interesting_name %6",
        "%c2_ui2 = firrtl.constant 2",
        // a + a >= 2
        "%7 = firrtl.geq %_GEN_1, %c2_ui2",
        "%_GEN_2 = firrtl.node interesting_name %7",
        "firrtl.contract {",
        // Require(a >= 1)
        "  firrtl.int.verif.require %_GEN_0",
        "  firrtl.int.verif.ensure %_GEN_2",
        // Ensure(a + a >= 2)
        "}"
      )
      NoArguments.verilogTest(parameter)(
        "module NoArguments"
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

          val b = Contract((io.a << 3) + io.a) { b =>
            Ensure(b === io.a * 9.U)
          }
          Assume((b === io.a * 9.U).I)

      SingleArgument.verilogTest(parameter)(
        "wire [11:0] _GEN = {4'h0, a};",
        "assume property ({1'h0, a, 3'h0} + _GEN == _GEN * 12'h9);"
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
            Ensure(u + v === p + q + r)
          }
          // Assume((u + v === p + q + r).I)

      MultipleArguments.verilogTest(parameter)(
        "wire [7:0] s = p ^ q;",
        "assume property ({1'h0, p & q | s & r, 1'h0}",
        "  + {2'h0, s ^ r} == {1'h0, {1'h0, p} + {1'h0, q}} + {2'h0, r});"
      )
