// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.circt.GeneratorBackend
import me.jiuyang.syntheke.zaozi.ZaoziBackend
import me.jiuyang.syntheke.demo.zaoziimpl.*
import me.jiuyang.syntheke.demo.harness.{TestHarness, TestHarnessGen}

/** Every registry entry bound to its zaozi generator — what the elaboration call receives, and the only place the
  * negotiation's side and zaozi's side meet.
  *
  * It sits with [[Soc]] because it is this design's table: `node/` declares the chip's entries and `harness/` the
  * testbench's, but which of them a design enacts, and by what, is the design's own statement.
  */
val backends: Seq[GeneratorBackend] = Seq(
  ZaoziBackend(TestHarness, TestHarnessGen),
  ZaoziBackend(Pll, PllGen),
  ZaoziBackend(Core, CoreGen),
  ZaoziBackend(Dma, DmaGen),
  ZaoziBackend(Xbar, XbarGen),
  ZaoziBackend(Dtm, DtmGen),
  ZaoziBackend(Dm, DmGen),
  ZaoziBackend(WidthBridge, WidthBridgeGen),
  ZaoziBackend(Uart, UartGen),
  ZaoziBackend(Gpio, GpioGen)
)
