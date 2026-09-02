// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}

val Uart = new GeneratorEntry[UartDeviceP]

/** The UART ([[UartDeviceGen]], the real device): a boundary inward node serving one address range on a 32-bit bus,
  * publishing its serial pins. Its baud divisor comes from the settled clock frequency; a clock too slow for the
  * requested baud rate fails here.
  */
final class UartNodes(
  name:           String,
  base:           Long,
  size:           Long,
  idCapacityBits: Int,
  baud:           Int
)(
  using GeneratorScope[UartDeviceP])
    extends Nodes:
  val clk    = inward(ClockDomain).uFn(_ => Right(()))
  val serial = outward(Serial).dFn(_ => Right(baud))
  parameters { view =>
    val freq = view.edgeOf(clk)
    if freq < baud * 8 then Left(Violation(s"clock $freq Hz too slow for $baud baud: needs 8 clocks per bit"))
    else
      val s = shapeOf(view, in)
      Right(UartDeviceP(freq / baud, s.addrBits, s.dataBits, s.idBits))
  }
  val in     = inward(Axi4).uFn(_ =>
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

def uartCtrl(
  base:           Long,
  size:           Long,
  idCapacityBits: Int,
  baud:           Int
)(
  using
  ws:             WrapperScope,
  name:           sourcecode.Name,
  file:           sourcecode.File,
  line:           sourcecode.Line
): UartNodes =
  generator(Uart)(new UartNodes(name.value, base, size, idCapacityBits, baud))
