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
