// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*
import me.jiuyang.syntheke.tests.axi.{
  AddressRange,
  Axi4,
  Axi4Xbar,
  AxiMasterParams,
  AxiMasterPort,
  AxiSlaveParams,
  AxiSlavePort,
  IdRange,
  TransferSizes
}
import upickle.default.ReadWriter

/** The syntheke wrap of the demo SoC's zaozi modules (`AxiZaoziModules.scala`) — how a plain zaozi IP gets onto the
  * negotiation graph. One section per IP: a registry entry typed by the IP's zaozi Parameter (negotiation computes it
  * as the FullParam, doc @sec-two-layer-params), the endpoint class declaring nodes and negotiation functions, and a
  * def binding both to the entry. [[axiBackends]] binds every entry to its zaozi generator — the only place the two
  * sides meet; the elaborator checks the zaozi ports against every settled interface at instantiation
  * (@dec-binding-check).
  *
  * The SoC that instantiates and wires these lives in [[AxiVerilogSpec]].
  */

private def entry[FP: ReadWriter](name: String) =
  new GeneratorEntry[FP](s"demo.axi.zaozi.$name")

/** The settled shape at one of the module's own nodes, read from its view. */
def shapeOf(view: EdgeView, n: Axi4.Node): AxiShape =
  val e = view.edgeOf(n)
  AxiShape(e.addrBits, e.dataBits, e.idBits)

// ============ Core: an AXI master with a local id space ============

val coreEntry = entry[CoreP]("Core")

/** One AXI master core: a boundary outward node with a local id space; the master is named after the instance. */
final class CorePorts(
  name:      String,
  idBits:    Int,
  maxFlight: Int
)(
  using GeneratorScope[CoreP])
    extends Endpoints:
  parameters(view => Right(CoreP(name, idBits, maxFlight, shapeOf(view, mem))))
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

// ============ Xbar: the n×m crossbar ============

val xbarEntry = entry[XbarP]("Xbar")

/** An n×m AXI crossbar: every input reaches every output. The endpoint class declares the nodes, the full dependency
  * matrix, the id-remapping dFns and aggregating uFns, and the port-shape FullParam.
  */
final class AxiXbarPorts(
  name:        String,
  ins:         Vector[String],
  outs:        Vector[String],
  arbitration: String
)(
  using GeneratorScope[XbarP])
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
    Right(
      XbarP(
        name,
        arbitration,
        ins.zip(inputs).map((n, b) => n -> shapeOf(view, b)),
        outs.zip(outputs).map((n, b) => n -> shapeOf(view, b))
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
  generator(xbarEntry)(new AxiXbarPorts(name.value, ins, outs, arbitration))

// ============ L2: a pass-through adapter with its own writeback master ============

val l2Entry = entry[L2P]("L2")

/** The L2 adapter: addresses and slave capabilities pass through; downstream it appends its writeback master after the
  * upstream id space (an adapter transforming Down).
  */
final class L2CachePorts(
  capacityKiB: Int
)(
  using GeneratorScope[L2P])
    extends Endpoints:
  val in             = inward(Axi4)
  val out            = outward(Axi4)
  private val (d, u) = depend(in, out)
  out.dFn { ctx =>
    val up = ctx(d)
    Right(AxiMasterPort(up.masters :+ AxiMasterParams("l2.wb", IdRange(up.endId, up.endId + 1), 2)))
  }
  in.uFn(ctx => Right(ctx(u)))
  parameters(view => Right(L2P(capacityKiB, shapeOf(view, in), shapeOf(view, out))))

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

val dramEntry = entry[DramP]("Dram")

/** A DRAM controller: one uncached address range on a 128-bit bus, named after the instance. */
final class DramPorts(
  name:           String,
  ranks:          Int,
  base:           Long,
  size:           Long,
  idCapacityBits: Int
)(
  using GeneratorScope[DramP])
    extends Endpoints:
  parameters(view => Right(DramP(ranks, shapeOf(view, in))))
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

val bridgeEntry = entry[BridgeP]("WidthBridge")

/** A width bridge: masters pass down unchanged; upstream it re-presents the narrow peripherals on the wide bus,
  * fragmenting bursts internally, so the supported transfer ceiling grows to its own limit.
  */
final class WidthBridgePorts(
  wideBeatBytes:       Int,
  maxUpstreamTransfer: Int
)(
  using GeneratorScope[BridgeP])
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
  parameters(view => Right(BridgeP(shapeOf(view, in), shapeOf(view, out))))

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

val uartEntry = entry[UartP]("Uart")

/** The UART: a boundary inward node serving one address range on a 32-bit bus. */
final class UartPorts(
  name:           String,
  base:           Long,
  size:           Long,
  idCapacityBits: Int
)(
  using GeneratorScope[UartP])
    extends Endpoints:
  parameters(view => Right(UartP(name, base, size, shapeOf(view, in))))
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

val gpioEntry = entry[GpioP]("Gpio")

/** The GPIO block: a boundary inward node serving one address range on a 32-bit bus. */
final class GpioPorts(
  name:           String,
  base:           Long,
  size:           Long,
  idCapacityBits: Int
)(
  using GeneratorScope[GpioP])
    extends Endpoints:
  parameters(view => Right(GpioP(name, base, size, shapeOf(view, in))))
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

/** Every registry entry bound to its zaozi generator — what the elaboration call receives. */
val axiBackends: Seq[GeneratorBackend] = Seq(
  ZaoziBackend(coreEntry, CoreGen),
  ZaoziBackend(xbarEntry, XbarGen),
  ZaoziBackend(l2Entry, L2Gen),
  ZaoziBackend(dramEntry, DramGen),
  ZaoziBackend(bridgeEntry, BridgeGen),
  ZaoziBackend(uartEntry, UartGen),
  ZaoziBackend(gpioEntry, GpioGen)
)
