// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.axi

import me.jiuyang.syntheke.*
import upickle.default.ReadWriter

/** The demo SoC's AXI IP library at the negotiation layer — the side an IP author ships. One section per IP: its
  * serializable FullParam record, the endpoint class declaring nodes and negotiation functions, and a def binding both
  * to a registry entry. The SoC that instantiates and wires these lives in [[AxiSocSpec]].
  */

private def entry[FP: ReadWriter](name: String) =
  new GeneratorEntry[FP](s"demo.axi.$name")

// ============ Core: an AXI master with a local id space ============

final case class CoreFull(name: String, idBits: Int, maxFlight: Int) derives ReadWriter

val coreEntry = entry[CoreFull]("Core")

/** One AXI master core: a boundary outward node with a local id space; the master is named after the instance. */
final class CorePorts(
  name:      String,
  idBits:    Int,
  maxFlight: Int
)(
  using GeneratorScope[CoreFull])
    extends Endpoints:
  parameters(_ => Right(CoreFull(name, idBits, maxFlight)))
  val mem =
    outward(Axi4).dFn(_ => Right(AxiMasterPort(Vector(AxiMasterParams(name, IdRange(0, 1 << idBits), maxFlight)))))

def core(
  idBits:    Int,
  maxFlight: Int
)(
  using
  ws:        WrapperScope,
  name:      sourcecode.Name,
  file:      sourcecode.File,
  line:      sourcecode.Line
): CorePorts =
  generator(coreEntry)(new CorePorts(name.value, idBits, maxFlight))

// ============ Dma: a bus-mastering DMA engine ============

final case class DmaFull(name: String, idBits: Int, maxFlight: Int) derives ReadWriter

val dmaEntry = entry[DmaFull]("Dma")

/** The DMA engine: an AXI master with its own small id space, named after the instance. */
final class DmaPorts(
  name:      String,
  idBits:    Int,
  maxFlight: Int
)(
  using GeneratorScope[DmaFull])
    extends Endpoints:
  parameters(_ => Right(DmaFull(name, idBits, maxFlight)))
  val mem =
    outward(Axi4).dFn(_ => Right(AxiMasterPort(Vector(AxiMasterParams(name, IdRange(0, 1 << idBits), maxFlight)))))

def dmaCtrl(
  idBits:    Int,
  maxFlight: Int
)(
  using
  ws:        WrapperScope,
  name:      sourcecode.Name,
  file:      sourcecode.File,
  line:      sourcecode.Line
): DmaPorts =
  generator(dmaEntry)(new DmaPorts(name.value, idBits, maxFlight))

// ============ Xbar: the n×m crossbar ============

final case class XbarInput(in: String, ids: IdRange) derives ReadWriter
final case class XbarRoute(out: String, address: Vector[AddressRange]) derives ReadWriter
final case class XbarFull(arbitration: String, inputs: Vector[XbarInput], routes: Vector[XbarRoute]) derives ReadWriter

val xbarEntry = entry[XbarFull]("Xbar")

/** An n×m AXI crossbar: every input reaches every output. The endpoint class declares the nodes, the full dependency
  * matrix, the id-remapping dFns and aggregating uFns, and a route-table FullParam.
  */
final class AxiXbarPorts(
  ins:         Vector[String],
  outs:        Vector[String],
  arbitration: String
)(
  using GeneratorScope[XbarFull])
    extends Endpoints:
  private val inputs  = ins.map { n =>
    given sourcecode.Name = sourcecode.Name(n)
    inward(Axi4)
  }
  private val outputs = outs.map { n =>
    given sourcecode.Name = sourcecode.Name(n)
    outward(Axi4)
  }

  /** Ports are declared by name, so they are looked up by name. */
  def input(n: String): Axi4.Inward =
    require(ins.contains(n), s"xbar has no input '$n' (inputs: ${ins.mkString(", ")})")
    inputs(ins.indexOf(n))

  def output(n: String): Axi4.Outward =
    require(outs.contains(n), s"xbar has no output '$n' (outputs: ${outs.mkString(", ")})")
    outputs(outs.indexOf(n))

  private val grid = outputs.map(out => inputs.map(in => depend(in, out)))
  outputs.zipWithIndex.foreach { (out, oi) =>
    val readers = grid(oi).map(_._1)
    out.dFn(ctx => Right(Axi4Xbar.mapInputs(readers.map(ctx(_)))))
  }
  inputs.zipWithIndex.foreach { (in, ii) =>
    val readers = grid.map(_(ii)._2)
    in.uFn(ctx => Axi4Xbar.aggregate(readers.map(ctx(_)), inputs.size))
  }
  parameters { view =>
    val inEdges = inputs.map(b => view.edgeOf(b))
    val local   = Axi4Xbar.localBits(inEdges.map(_.master))
    Right(
      XbarFull(
        arbitration = arbitration,
        inputs = ins.zip(inEdges).zipWithIndex.map { case ((n, e), i) =>
          XbarInput(n, IdRange(i << local, (i << local) + e.master.endId))
        },
        routes = outs.zip(outputs).map((n, b) => XbarRoute(n, view.edgeOf(b).slave.slaves.flatMap(_.address)))
      )
    )
  }

def axiXbar(
  ins:         Vector[String],
  outs:        Vector[String],
  arbitration: String
)(
  using
  ws:          WrapperScope,
  name:        sourcecode.Name,
  file:        sourcecode.File,
  line:        sourcecode.Line
): AxiXbarPorts =
  generator(xbarEntry)(new AxiXbarPorts(ins, outs, arbitration))

// ============ L2: a pass-through adapter with its own writeback master ============

final case class L2Full(capacityKiB: Int, upstreamIdBits: Int, downstreamIdBits: Int) derives ReadWriter

val l2Entry = entry[L2Full]("L2")

/** The L2 adapter: addresses and slave capabilities pass through; downstream it appends its writeback master after the
  * upstream id space (an adapter transforming Down).
  */
final class L2CachePorts(
  capacityKiB: Int
)(
  using GeneratorScope[L2Full])
    extends Endpoints:
  val in             = inward(Axi4)
  val out            = outward(Axi4)
  private val (d, u) = depend(in, out)
  out.dFn { ctx =>
    val up = ctx(d)
    Right(AxiMasterPort(up.masters :+ AxiMasterParams("l2.wb", IdRange(up.endId, up.endId + 1), 2)))
  }
  in.uFn(ctx => Right(ctx(u)))
  parameters { view =>
    Right(
      L2Full(
        capacityKiB = capacityKiB,
        upstreamIdBits = view.edgeOf(in).idBits,
        downstreamIdBits = view.edgeOf(out).idBits
      )
    )
  }

def l2Cache(
  capacityKiB: Int
)(
  using
  ws:          WrapperScope,
  name:        sourcecode.Name,
  file:        sourcecode.File,
  line:        sourcecode.Line
): L2CachePorts =
  generator(l2Entry)(new L2CachePorts(capacityKiB))

// ============ DRAM: the uncached memory slave ============

final case class DramFull(ranks: Int, addrBits: Int, dataBits: Int, idBits: Int, masters: Vector[String])
    derives ReadWriter

val dramEntry = entry[DramFull]("Dram")

/** A DRAM controller: one uncached address range on a 128-bit bus, named after the instance. */
final class DramPorts(
  name:           String,
  ranks:          Int,
  base:           Long,
  size:           Long,
  idCapacityBits: Int
)(
  using GeneratorScope[DramFull])
    extends Endpoints:
  parameters { view =>
    val e = view.edgeOf(in)
    Right(DramFull(ranks, e.addrBits, e.dataBits, e.idBits, e.master.masters.map(_.name)))
  }
  val in = inward(Axi4).uFn(_ =>
    Right(
      AxiSlavePort(
        slaves = Vector(
          AxiSlaveParams(
            name,
            Vector(AddressRange(base, size)),
            "UNCACHED",
            true,
            TransferSizes(1, 64),
            TransferSizes(1, 64)
          )
        ),
        beatBytes = 16,
        idCapacityBits = idCapacityBits,
        minLatency = 8
      )
    )
  )

def dramCtrl(
  ranks:          Int,
  base:           Long,
  size:           Long,
  idCapacityBits: Int
)(
  using
  ws:             WrapperScope,
  name:           sourcecode.Name,
  file:           sourcecode.File,
  line:           sourcecode.Line
): DramPorts =
  generator(dramEntry)(new DramPorts(name.value, ranks, base, size, idCapacityBits))

// ============ WidthBridge: wide to narrow ============

final case class BridgeFull(wideBeatBytes: Int, narrowBeatBytes: Int, idBits: Int) derives ReadWriter

val bridgeEntry = entry[BridgeFull]("WidthBridge")

/** A width bridge: masters pass down unchanged; upstream it re-presents the narrow peripherals on the wide bus,
  * fragmenting bursts internally, so the supported transfer ceiling grows to its own limit.
  */
final class WidthBridgePorts(
  wideBeatBytes:       Int,
  maxUpstreamTransfer: Int
)(
  using GeneratorScope[BridgeFull])
    extends Endpoints:
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
  parameters(view => Right(BridgeFull(wideBeatBytes, narrowBeatBytes = 4, idBits = view.edgeOf(in).idBits)))

def widthBridge(
  wideBeatBytes:       Int,
  maxUpstreamTransfer: Int
)(
  using
  ws:                  WrapperScope,
  name:                sourcecode.Name,
  file:                sourcecode.File,
  line:                sourcecode.Line
): WidthBridgePorts =
  generator(bridgeEntry)(new WidthBridgePorts(wideBeatBytes, maxUpstreamTransfer))

// ============ Uart: a memory-mapped peripheral ============

final case class UartFull(name: String, base: Long, size: Long, dataBits: Int, idBits: Int) derives ReadWriter

val uartEntry = entry[UartFull]("Uart")

/** The UART: a boundary inward node serving one address range on a 32-bit bus. */
final class UartPorts(
  name:           String,
  base:           Long,
  size:           Long,
  idCapacityBits: Int
)(
  using GeneratorScope[UartFull])
    extends Endpoints:
  parameters { view =>
    val e = view.edgeOf(in)
    Right(UartFull(name, base, size, e.dataBits, e.idBits))
  }
  val in = inward(Axi4).uFn(_ =>
    Right(
      AxiSlavePort(
        slaves = Vector(
          AxiSlaveParams(
            name,
            Vector(AddressRange(base, size)),
            "PUT_EFFECTS",
            false,
            TransferSizes(1, 4),
            TransferSizes(1, 4)
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
  idCapacityBits: Int
)(
  using
  ws:             WrapperScope,
  name:           sourcecode.Name,
  file:           sourcecode.File,
  line:           sourcecode.Line
): UartPorts =
  generator(uartEntry)(new UartPorts(name.value, base, size, idCapacityBits))

// ============ Gpio: a memory-mapped peripheral ============

final case class GpioFull(name: String, base: Long, size: Long, dataBits: Int, idBits: Int) derives ReadWriter

val gpioEntry = entry[GpioFull]("Gpio")

/** The GPIO block: a boundary inward node serving one address range on a 32-bit bus. */
final class GpioPorts(
  name:           String,
  base:           Long,
  size:           Long,
  idCapacityBits: Int
)(
  using GeneratorScope[GpioFull])
    extends Endpoints:
  parameters { view =>
    val e = view.edgeOf(in)
    Right(GpioFull(name, base, size, e.dataBits, e.idBits))
  }
  val in = inward(Axi4).uFn(_ =>
    Right(
      AxiSlavePort(
        slaves = Vector(
          AxiSlaveParams(
            name,
            Vector(AddressRange(base, size)),
            "PUT_EFFECTS",
            false,
            TransferSizes(1, 4),
            TransferSizes(1, 4)
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
  idCapacityBits: Int
)(
  using
  ws:             WrapperScope,
  name:           sourcecode.Name,
  file:           sourcecode.File,
  line:           sourcecode.Line
): GpioPorts =
  generator(gpioEntry)(new GpioPorts(name.value, base, size, idCapacityBits))
