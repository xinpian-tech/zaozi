// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.circt.GeneratorBackend
import me.jiuyang.syntheke.zaozi.ZaoziBackend
import me.jiuyang.syntheke.demo.zaoziimpl.*
import me.jiuyang.syntheke.demo.zaoziimpl.harness.TestHarnessGen

/** Every registry entry bound to its zaozi generator — what the elaboration call receives, and the only place the
  * negotiation's side and zaozi's side meet. It is its own file because it belongs to neither: [[AxiLibrary]] declares
  * the chip's entries, [[Harness]] the testbench's, and both are elaborated through this table.
  */
val axiBackends: Seq[GeneratorBackend] = Seq(
  ZaoziBackend(TestHarness, TestHarnessGen),
  ZaoziBackend(Pll, PllGen),
  ZaoziBackend(Core, CoreDeviceGen),
  ZaoziBackend(Dma, DmaDeviceGen),
  ZaoziBackend(Xbar, XbarGen),
  ZaoziBackend(Dtm, DtmGen),
  ZaoziBackend(Dm, DmGen),
  ZaoziBackend(WidthBridge, BridgeDeviceGen),
  ZaoziBackend(Uart, UartDeviceGen),
  ZaoziBackend(Gpio, GpioDeviceGen)
)
