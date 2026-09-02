// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.demo.axi.{Axi4, TransferSizes}

val WidthBridge = new GeneratorEntry[BridgeDeviceP]

/** A width bridge: masters pass down unchanged; upstream it re-presents the narrow peripherals on the wide bus,
  * fragmenting bursts internally, so the supported transfer ceiling grows to its own limit.
  */
final class WidthBridgeNodes(
  wideBeatBytes:       Int,
  maxUpstreamTransfer: Int
)(
  using GeneratorScope[BridgeDeviceP])
    extends Nodes:
  val clk            = inward(ClockDomain).uFn(_ => Right(()))
  val in             = inward(Axi4)
  val out            = outward(Axi4)
  private val (d, u) = depend(in, out)
  out.dFn(ctx => Right(ctx(d)))
  in.uFn { ctx =>
    val narrow = ctx(u)
    Right(
      narrow.copy(
        beatBytes = wideBeatBytes,
        slaves = narrow.slaves.map(s =>
          s.copy(
            supportsRead = TransferSizes(s.supportsRead.min, maxUpstreamTransfer),
            supportsWrite = TransferSizes(s.supportsWrite.min, maxUpstreamTransfer)
          )
        )
      )
    )
  }
  parameters(view => Right(BridgeDeviceP(shapeOf(view, in), shapeOf(view, out))))

def widthBridge(
  wideBeatBytes:       Int,
  maxUpstreamTransfer: Int
)(
  using
  ws:                  WrapperScope,
  name:                sourcecode.Name,
  file:                sourcecode.File,
  line:                sourcecode.Line
): WidthBridgeNodes =
  generator(WidthBridge)(new WidthBridgeNodes(wideBeatBytes, maxUpstreamTransfer))
