// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
// The parameter of zaozi PR #159's power/PowerControl.scala, copied for the demo; delete once #159 lands.
package me.jiuyang.stdlib.power

import me.jiuyang.zaozi.*

case class PowerControlParameter(
  hasSwitch:            Boolean,
  waitDependencyCycles: BigInt,
  settleOnCycles:       BigInt,
  settleOffCycles:      BigInt)
    extends Parameter:
  val delays       = Seq(waitDependencyCycles, settleOnCycles, settleOffCycles)
  require(delays.forall(_ >= 0), "power delays must not be negative")
  val counterWidth = (delays.max - 1).max(0).bitLength.max(1)

given upickle.default.ReadWriter[PowerControlParameter] = upickle.default.macroRW
