// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.demo.axi.{Axi4, AxiMasterParams, AxiMasterPort, IdRange}

val Dma = new GeneratorEntry[DmaDeviceP]

/** The DMA engine ([[DmaDeviceGen]], the real device): an AXI master with its own small id space, walking a write
  * window from `targetBase`.
  */
final class DmaNodes(
  name:       String,
  idBits:     Int,
  maxFlight:  Int,
  targetBase: Long,
  windowLog2: Int
)(
  using GeneratorScope[DmaDeviceP])
    extends Nodes:
  val clk = inward(ClockDomain).uFn(_ => Right(()))
  parameters { view =>
    val s = shapeOf(view, mem)
    Right(DmaDeviceP(targetBase, windowLog2, s.addrBits, s.dataBits, s.idBits))
  }
  val mem =
    outward(Axi4).dFn(_ =>
      Right(AxiMasterPort(Vector(AxiMasterParams(name, IdRange(0, 1 << idBits), maxFlight = Some(maxFlight)))))
    )

def dmaCtrl(
  idBits:     Int,
  maxFlight:  Int,
  targetBase: Long,
  windowLog2: Int
)(
  using
  ws:         WrapperScope,
  name:       sourcecode.Name,
  file:       sourcecode.File,
  line:       sourcecode.Line
): DmaNodes =
  generator(Dma)(new DmaNodes(name.value, idBits, maxFlight, targetBase, windowLog2))
