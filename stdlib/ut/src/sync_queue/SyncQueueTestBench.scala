// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.ut

import java.lang.foreign.Arena
import scala.annotation.experimental

import me.jiuyang.stdlib.queue.default.{SyncQueue, SyncQueueIO, SyncQueueParameter, given}
import me.jiuyang.utlib.{UT, UTDut}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import org.llvm.circt.scalalib.dialect.seq.operation.{ToClockApi, given}
import org.llvm.circt.scalalib.dialect.sim.operation.DPIDirection
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, LocationApi, given}

/** UT entry for the production SyncQueue architecture. */
@experimental
object SyncQueueTestBench extends UT[SyncQueueParameter, SyncQueueIO]:
  def testbench(
    parameter: SyncQueueParameter
  )(
    using Arena,
    Context,
    Block
  ): Unit =
    val tb  = summon[TBApi]
    val sim = summon[SimApi]
    import DPIDirection.{Out, Return}

    val io        = SyncQueue.interface(parameter)
    val driven    = Seq(
      "reset_n"      -> io.resetN,
      "push_n"       -> io.pushRequestN,
      "pop_n"        -> io.popRequestN,
      "diagnostic_n" -> io.diagnosticN,
      "data_in"      -> io.dataIn
    )
    val arguments = driven.map { case (name, field) =>
      DpiArg(name, Out, field.dataType.width)
    } ++ Seq(DpiArg("done", Out, 1), DpiArg("status", Return, 32))
    val step      = sim.dpiFunction("step", Some("zaozi_step"), arguments)
    tb.module("SyncQueueUT"):
      val clock = tb.clock("clk", period = 10)
      val seqClock = summon[ToClockApi].op(clock, summon[LocationApi].locationUnknownGet)
      seqClock.operation.appendToBlock()
      val call = sim.dpiCall(step, seqClock.result, clock, Seq.empty)
      UTDut.instantiate(
        SyncQueue,
        parameter,
        "dut",
        driven.map { case (name, field) => field.name -> call(name) }.toMap + (io.clock.name -> clock)
      )
      sim.clockedTerminate(seqClock.result, call("done"), success = true)
