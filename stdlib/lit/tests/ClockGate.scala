// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{runner} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS
// DEFINE: %{test} = %{runner} --main-class me.jiuyang.stdlib.clock.ClockGate %s --
// DEFINE: %{check} = %{runner} --main-class me.jiuyang.stdlib.prcm.ClockGateCheck %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/positive.json --positive true --clockDuringReset false
// RUN: cd %t.dir && %{test} design %t.dir/positive.json
// RUN: firtool %t.dir/ClockGate_positivetrue_clockDuringResetfalse.mlirbc --strip-debug-info | FileCheck %s --check-prefixes=COVER,DIRECT,POSITIVE
// RUN: %{test} config %t.dir/negative.json --positive false --clockDuringReset false
// RUN: cd %t.dir && %{test} design %t.dir/negative.json
// RUN: firtool %t.dir/ClockGate_positivefalse_clockDuringResetfalse.mlirbc --strip-debug-info | FileCheck %s --check-prefixes=COVER,DIRECT,NEGATIVE
// RUN: %{test} config %t.dir/reset-positive.json --positive true --clockDuringReset true
// RUN: cd %t.dir && %{test} design %t.dir/reset-positive.json
// RUN: firtool %t.dir/ClockGate_positivetrue_clockDuringResettrue.mlirbc --strip-debug-info | FileCheck %s --check-prefixes=COVER,RESET,POSITIVE
// RUN: %{test} config %t.dir/reset-negative.json --positive false --clockDuringReset true
// RUN: cd %t.dir && %{test} design %t.dir/reset-negative.json
// RUN: firtool %t.dir/ClockGate_positivefalse_clockDuringResettrue.mlirbc --strip-debug-info | FileCheck %s --check-prefixes=COVER,RESET,NEGATIVE
// RUN: %{check} %t.dir/positive.json %t.dir/proof-positive
// RUN: %{check} %t.dir/negative.json %t.dir/proof-negative
// RUN: %{check} %t.dir/reset-positive.json %t.dir/proof-reset-positive
// RUN: %{check} %t.dir/reset-negative.json %t.dir/proof-reset-negative
// RUN: %{check} %t.dir/negative.json %t.dir/proof-cells '[{"name":"ICG","sequential":{"type":"GatePositive","clock":"CK","enable":"E","output":"Q"}},{"name":"INV","inputs":["A"],"output":"Y","function":[{"Y":"!A"}]}]'
// RUN: for query in $(find %t.dir -name '*.smt2'); do z3 $query | FileCheck $query --check-prefix=EXPECT || exit 1; done
// RUN: rm -rf %t.dir

// COVER: gate_requested:
// COVER: cover property
// COVER: gate_disable_requested:
// COVER: cover property
// COVER: gate_test_enable:
// COVER: cover property
// RESET: gate_reset_enable:
// RESET: cover property

// COVER-LABEL: module ClockGate_{{positive(true|false)_clockDuringReset(true|false)}}(
// COVER-NOT: ZaoziClockMux
// RESET: wire [[RESET:[a-zA-Z_0-9]+]] = ~_receiver_synchronizedReset;
// POSITIVE: ZaoziClockGatePositive gate (
// NEGATIVE: ZaoziClockGateNegative gate (
// COVER-NEXT: .a{{ +}}(clock),
// DIRECT-NEXT: .enable{{ +}}(enable | testEnable),
// RESET-NEXT: .enable{{ +}}(enable | testEnable | [[RESET]]),
// COVER-NEXT: .outClock (output_0)
// RESET: SynchronizedReset_stages2_activeLow receiver (
// RESET-NEXT: .clock{{ +}}(clock),
// RESET-NEXT: .reset{{ +}}(resetN),
// COVER-NOT: ZaoziClockMux
// COVER: endmodule

package me.jiuyang.stdlib.prcm

import java.lang.foreign.Arena
import scala.util.chaining.*

import me.jiuyang.stdlib.clock.{ClockCellDeclaration, ClockCellLibrary, ClockGate, ClockGateParameter, given}
import me.jiuyang.zaozi.default.Elaborate
import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, given}

object ClockGateCheck:
  def main(args: Array[String]): Unit =
    // Declared cells add a Library case to every clock role; the proof still takes the model.
    val config  = upickle.default
      .read[ClockGateParameter](os.read(os.Path(args(0), os.pwd)))
      .pipe(config =>
        args.lift(2).fold(config)(cells =>
          config.copy(library = ClockCellLibrary.resolve(upickle.default.read[Seq[ClockCellDeclaration]](cells)))
        )
      )
    val arena   = Arena.ofConfined()
    val queries =
      try
        given Arena   = arena
        given Context = summon[ContextApi].contextCreate
        PRCMStateCut.prepare
        val module   = Elaborate(ClockGate, config)
        val metadata = PRCMStateCut.lower(module, ClockGate.moduleName(config))
        PRCMRelation.use(module, metadata): relation =>
          import relation.*
          val latches  = states.filter(_("kind").str == "latch")
          val receiver = states.filter(_("kind").str == "seq.firreg")
          require(latches.size == 1 && receiver.size == (if config.clockDuringReset then 2 else 0))
          val latch    = latches.head
          val clock    = bit("clock")
          val inReset  = receiver.lastOption.fold(bool(false))(stage => not(asBool(value(stage))))
          val enable   = or(Seq(bit("enable"), bit("testEnable"), inReset))
          val output   = if config.positive then and(clock, asBool(value(latch))) else or(Seq(clock, not(asBool(value(latch)))))
          val violations = Seq(
            violation("transparency", not(equal(asBool(port(latch, "enable")), if config.positive then not(clock) else clock))),
            violation("captured_enable", not(equal(asBool(port(latch, "d")), enable))),
            violation("output", not(equal(bit("output"), output)))
          )
          Seq(PRCMQuery.sat("feasible", query()), PRCMQuery.unsat("equivalent", query(Some(or(violations)))))
      finally arena.close()
    PRCMQuery.write(os.Path(args(1), os.pwd), queries)
