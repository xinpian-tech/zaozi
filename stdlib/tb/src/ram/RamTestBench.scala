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
