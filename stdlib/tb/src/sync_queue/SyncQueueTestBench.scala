// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.tb

import me.jiuyang.stdlib.{ClockGen, ClockParameter}
import me.jiuyang.stdlib.queue.default.{
  SyncQueue,
  SyncQueueIO,
  SyncQueueLayers,
  SyncQueueParameter,
  SyncQueueProbe,
  given
}
import me.jiuyang.tblib.*
import me.jiuyang.tblib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{generator, given}

@generator
object SyncQueueTestBench extends TestbenchGenerator[SyncQueueParameter, SyncQueueLayers, SyncQueueIO, SyncQueueProbe]:
  val dut   = SyncQueue
  val clock = ClockGen(ClockParameter(periodNs = 10))

  override def moduleName(parameter: SyncQueueParameter): String = "SyncQueueTestBench"

  def simulation(parameter: SyncQueueParameter) =
    val step   = dpiFunction(
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
    val values = dpiCall(step)

    dut.io.resetN       := values("resetN")
    dut.io.pushRequestN := values("pushRequestN")
    dut.io.popRequestN  := values("popRequestN")
    dut.io.diagnosticN  := values("diagnosticN")
    dut.io.dataIn       := values("dataIn")

    finish(values("done"))
