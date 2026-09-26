// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import me.jiuyang.stdlib.clock.{ClockCell, ClockCellKind, ClockCellParameter}
import me.jiuyang.smtlib.parser.Z3Status
import utest.*

object PRCMClockCellSpec extends TestSuite:
  val tests = Tests:
    test("Verilog clock logic implements its pin functions"):
      Seq(ClockCellKind.Buffer, ClockCellKind.Inverter, ClockCellKind.Or, ClockCellKind.Mux, ClockCellKind.Xor).foreach:
        kind =>
          val config   = ClockCellParameter(kind)
          val hardware = os
            .proc("circt-verilog", "--format=sv", "--ir-hw", s"--top=${ClockCell.moduleName(config)}", "-")
            .call(stdin = ClockCell.source, stderr = os.Pipe, timeout = 30000)
            .out
            .text()
          val ir       = os
            .proc(
              "circt-opt",
              "--convert-hw-to-smt=for-smtlib-export",
              "--convert-comb-to-smt",
              "--reconcile-unrealized-casts"
            )
            .call(stdin = hardware, stderr = os.Pipe, timeout = 30000)
            .out
            .text()
          val inputs   = Seq("a") ++ Option.when(config.binary)("b") ++ Option.when(kind == ClockCellKind.Mux)("select")
          val metadata = ujson.Obj(
            "symbols"     -> ujson.Arr.from(inputs :+ "outClock"),
            "inputCount"  -> inputs.size,
            "outputCount" -> 1,
            "states"      -> ujson.Arr()
          )
          val queries  = PRCMRelation.use(ir, metadata): relation =>
            import relation.*
            val expected = kind match
              case ClockCellKind.Buffer   => bit("a")
              case ClockCellKind.Inverter => not(bit("a"))
              case ClockCellKind.Or       => or(Seq(bit("a"), bit("b")))
              case ClockCellKind.Mux      => ite(bit("select"), bit("b"), bit("a"))
              case ClockCellKind.Xor      => not(equal(bit("a"), bit("b")))
              case _                      => throw new IllegalArgumentException("not a combinational clock cell")
            Seq(Z3Status.Sat -> query(), Z3Status.Unsat -> query(Some(not(equal(bit("outClock"), expected)))))
          queries.foreach: (expected, query) =>
            val result = query.check(30000)
            assert(result.status == expected)
