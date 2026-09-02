// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.circt.GeneratorBackend
import me.jiuyang.syntheke.zaozi.ZaoziBackend
import me.jiuyang.syntheke.demo.zaoziimpl.*
import me.jiuyang.syntheke.demo.harness.{TestHarness, TestHarnessGen}

/** Every registry entry bound to its zaozi generator — what the elaboration call receives, and the only place the
  * negotiation's side and zaozi's side meet. It is its own file because it belongs to neither: `node/` declares the
  * chip's entries, [[Harness]] the testbench's, and both are elaborated through this table.
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
