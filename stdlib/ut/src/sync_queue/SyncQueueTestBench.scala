// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.ut

import me.jiuyang.stdlib.queue.default.{SyncQueue, SyncQueueLayers, SyncQueueParameter, SyncQueueProbe, given}
import me.jiuyang.tblib.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.capi.dialect.sim.DPIDirection

/** The DUT wiring and simulation behavior of the SyncQueue unit testbench. */
class SyncQueueTestBenchIO(parameter: SyncQueueParameter) extends HWBundle(parameter):
  val clock        = Flipped(Clock())
  val resetN       = Flipped(Reset())
  val pushRequestN = Flipped(Bool())
  val popRequestN  = Flipped(Bool())
  val diagnosticN  = Flipped(Bool())
  val dataIn       = Flipped(UInt(parameter.width))

@generator
object SyncQueueTestBench
    extends TestbenchGenerator[SyncQueueParameter, SyncQueueLayers, SyncQueueTestBenchIO, SyncQueueProbe]:
  val dut = SyncQueue

  override def moduleName(parameter: SyncQueueParameter): String = "SyncQueueTestBench"
  def clockPeriodNs(parameter:       SyncQueueParameter): Long   = 10

  def simulation(parameter: SyncQueueParameter) =
    val tb     = summon[Testbench[SyncQueueTestBenchIO]]
    val step   = tb.dpiFunction(
      "step",
      Some("zaozi_step"),
      Seq(
        DpiArg("resetN", DPIDirection.Out, 1),
        DpiArg("pushRequestN", DPIDirection.Out, 1),
        DpiArg("popRequestN", DPIDirection.Out, 1),
        DpiArg("diagnosticN", DPIDirection.Out, 1),
        DpiArg("dataIn", DPIDirection.Out, parameter.width),
        DpiArg("done", DPIDirection.Out, 1),
        DpiArg("status", DPIDirection.Return, 32, signed = true)
      )
    )
    val values = tb.dpiCall(step)
    tb.io.resetN       := values("resetN")
    tb.io.pushRequestN := values("pushRequestN")
    tb.io.popRequestN  := values("popRequestN")
    tb.io.diagnosticN  := values("diagnosticN")
    tb.io.dataIn       := values("dataIn")
    tb.finish(values("done"))
