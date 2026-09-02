// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.demo.axi.{AddressSet, Axi4, AxiSlaveParams, AxiSlavePort, RegionType, TransferSizes}

val Gpio = new GeneratorEntry[GpioDeviceP]

/** The GPIO block ([[GpioDeviceGen]], the real device): a boundary inward node serving one address range on a 32-bit
  * bus, publishing its pin bank.
  */
final class GpioNodes(
  name:           String,
  base:           Long,
  size:           Long,
  idCapacityBits: Int,
  width:          Int
)(
  using GeneratorScope[GpioDeviceP])
    extends Nodes:
  val clk  = inward(ClockDomain).uFn(_ => Right(()))
  val pins = outward(GpioPins).dFn(_ => Right(width))
  parameters { view =>
    val s = shapeOf(view, in)
    Right(GpioDeviceP(width, s.addrBits, s.dataBits, s.idBits))
  }
  val in   = inward(Axi4).uFn(_ =>
    Right(
      AxiSlavePort(
        slaves = Vector(
          AxiSlaveParams(
            name,
            AddressSet.misaligned(base, size),
            RegionType.PutEffects,
            executable = false,
            supportsWrite = TransferSizes(1, 4),
            supportsRead = TransferSizes(1, 4)
          )
        ),
        beatBytes = 4,
        idCapacityBits = idCapacityBits,
        minLatency = 1
      )
    )
  )

def gpioCtrl(
  base:           Long,
  size:           Long,
  idCapacityBits: Int,
  width:          Int
)(
  using
  ws:             WrapperScope,
  name:           sourcecode.Name,
  file:           sourcecode.File,
  line:           sourcecode.Line
): GpioNodes =
  generator(Gpio)(new GpioNodes(name.value, base, size, idCapacityBits, width))
