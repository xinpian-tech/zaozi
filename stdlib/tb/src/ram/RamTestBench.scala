// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.tb

import me.jiuyang.stdlib.{ClockGen, ClockParameter}
import me.jiuyang.stdlib.default.{Ram, RamIO, RamLayers, RamParameter, RamProbe, given}
import me.jiuyang.tblib.*
import me.jiuyang.tblib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{generator, given}

@generator
object RamTestBench extends TestbenchGenerator[RamParameter, RamLayers, RamIO, RamProbe]:
  val dut   = Ram
  val clock = ClockGen(ClockParameter(periodNs = 10))

  override def moduleName(parameter: RamParameter): String = "RamTestBench"

  def simulation(parameter: RamParameter) =
    val step   = dpiFunction(
      "step",
      Some("zaozi_step"),
      Seq(
        DpiArg("resetN", DPIDirection.Out, 1),
        DpiArg("chipSelectN", DPIDirection.Out, 1),
        DpiArg("writeN", DPIDirection.Out, 1),
        DpiArg("readAddress", DPIDirection.Out, parameter.addressWidth),
        DpiArg("writeAddress", DPIDirection.Out, parameter.addressWidth),
        DpiArg("writeData", DPIDirection.Out, parameter.width),
        DpiArg("done", DPIDirection.Out, 1),
        DpiArg("status", DPIDirection.Return, 32, signed = true)
      )
    )
    val values = dpiCall(step)

    dut.io.resetN       := values("resetN")
    dut.io.chipSelectN  := values("chipSelectN")
    dut.io.writeN       := values("writeN")
    dut.io.readAddress  := values("readAddress")
    dut.io.writeAddress := values("writeAddress")
    dut.io.writeData    := values("writeData")

    finish(values("done"))
