// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{runner} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS
// DEFINE: %{test} = %{runner} --main-class me.jiuyang.stdlib.clock.ClockGate %s --
// DEFINE: %{check} = %{runner} --main-class me.jiuyang.stdlib.prcm.ClockGateCheck %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/positive.json --positive true --clockDuringReset false
// RUN: cd %t.dir && %{test} design %t.dir/positive.json
// RUN: firld --base-circuit=ClockGate_positivetrue_clockDuringResetfalse %t.dir/*.mlirbc -o %t.dir/positive.mlir
// RUN: %{check} %t.dir/positive.json %t.dir/positive.mlir %t.dir/proof-positive
// RUN: firtool %t.dir/positive.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv | FileCheck %s --check-prefixes=COVER
// RUN: %{test} config %t.dir/negative.json --positive false --clockDuringReset false
// RUN: cd %t.dir && %{test} design %t.dir/negative.json
// RUN: firld --base-circuit=ClockGate_positivefalse_clockDuringResetfalse %t.dir/*.mlirbc -o %t.dir/negative.mlir
// RUN: %{check} %t.dir/negative.json %t.dir/negative.mlir %t.dir/proof-negative
// RUN: firtool %t.dir/negative.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv | FileCheck %s --check-prefixes=COVER
// RUN: %{test} config %t.dir/reset-positive.json --positive true --clockDuringReset true
// RUN: cd %t.dir && %{test} design %t.dir/reset-positive.json
// RUN: firld --base-circuit=ClockGate_positivetrue_clockDuringResettrue %t.dir/*.mlirbc -o %t.dir/reset-positive.mlir
// RUN: %{check} %t.dir/reset-positive.json %t.dir/reset-positive.mlir %t.dir/proof-reset-positive
// RUN: firtool %t.dir/reset-positive.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv | FileCheck %s --check-prefixes=COVER,RESET
// RUN: %{test} config %t.dir/reset-negative.json --positive false --clockDuringReset true
// RUN: cd %t.dir && %{test} design %t.dir/reset-negative.json
// RUN: firld --base-circuit=ClockGate_positivefalse_clockDuringResettrue %t.dir/*.mlirbc -o %t.dir/reset-negative.mlir
// RUN: %{check} %t.dir/reset-negative.json %t.dir/reset-negative.mlir %t.dir/proof-reset-negative
// RUN: firtool %t.dir/reset-negative.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv | FileCheck %s --check-prefixes=COVER,RESET
// RUN: rm -rf %t.dir

// COVER: gate_requested:
// COVER: cover property
// COVER: gate_disable_requested:
// COVER: cover property
// COVER: gate_test_bypass:
// COVER: cover property
// RESET: gate_reset_bypass:
// RESET: cover property

package me.jiuyang.stdlib.prcm

import me.jiuyang.stdlib.clock.{ClockGate, ClockGateParameter, given}
import me.jiuyang.smtlib.parser.Z3Status

object ClockGateCheck:
  def main(args: Array[String]): Unit =
    val config = upickle.default.read[ClockGateParameter](os.read(os.Path(args(0), os.pwd)))
    val design = os.Path(args(1), os.pwd)
    val directory = os.Path(args(2), os.pwd)
    os.makeDir.all(directory)
    val (ir, metadata) = PRCMStateCut.lower(os.read(design), ClockGate.moduleName(config),
      os.read(design / os.up / "ClockCells.sv"))
    val queries = PRCMRelation.use(ir, metadata): relation =>
      import relation.*
      require(states.size == 1 && states.head("kind").str == "llhd.sig")
      val latch = states.head
      val clock = bit("clock")
      val enable = or(Seq(bit("enable"), bit("testEnable")))
      val bypass = or(Seq(bit("testEnable"), if config.clockDuringReset then not(bit("resetN")) else bool(false)))
      val active = if config.positive then and(clock, asBool(value(latch))) else or(Seq(clock, asBool(value(latch))))
      val expected = ite(bypass, clock, active)
      val violations = Seq(
        violation("transparency", not(equal(asBool(port(latch, "enable")), if config.positive then not(clock) else clock))),
        violation("captured_enable", and(asBool(port(latch, "enable")),
          not(equal(asBool(port(latch, "d")), if config.positive then enable else not(enable))))),
        violation("output", not(equal(bit("output"), expected))))
      Seq("feasible" -> query(), "equivalent" -> query(Some(or(violations))))
    queries.foreach: (name, query) =>
      val expected = if name == "feasible" then Z3Status.Sat else Z3Status.Unsat
      os.write(directory / s"$name.smt2", query.replay)
      val result = query.check(30000)
      require(result.status == expected, s"$name: $result")
      require(os.proc("z3", (directory / s"$name.smt2").toString).call().out.text().trim == expected.toString.toLowerCase)
