// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*
import me.jiuyang.syntheke.tests.zaoziimpl.{*, given}
import me.jiuyang.syntheke.tests.axi.{
  AddressSet,
  Axi4,
  Axi4Xbar,
  AxiMasterParams,
  AxiMasterPort,
  AxiSlaveParams,
  AxiSlavePort,
  IdRange,
  RegionType,
  TransferSizes
}

/** The syntheke wrap of the demo SoC's zaozi modules (`AxiZaoziModules.scala`) — how a plain zaozi IP gets onto the
  * negotiation graph. One section per IP: a registry entry typed by the IP's zaozi Parameter (negotiation computes it
  * as the FullParam, doc @sec-two-layer-params), the endpoint class declaring nodes and negotiation functions, and a
  * def binding both to the entry. [[axiBackends]] binds every entry to its zaozi generator — the only place the two
  * sides meet; the elaborator checks the zaozi ports against every settled interface at instantiation
  * (@dec-binding-check).
  *
  * The SoC that instantiates and wires these lives in [[AxiVerilogSpec]].
  */

/** The settled shape at one of the module's own nodes, read from its view. */
def shapeOf(view: EdgeView, n: Axi4.Node): AxiShape =
  val e = view.edgeOf(n)
  AxiShape(e.addrBits, e.dataBits, e.idBits)

// ============ Core: an AXI master with a local id space ============

val Core = new GeneratorEntry[CoreP]

/** One AXI master core: a boundary outward node with a local id space; the master is named after the instance. */
final class CoreNodes(
  name:      String,
  idBits:    Int,
  maxFlight: Int
)(
  using GeneratorScope[CoreP])
    extends Nodes:
  val clk = inward(ClockDomain).uFn(_ => Right(()))
  parameters(view => Right(CoreP(name, idBits, maxFlight, shapeOf(view, mem))))
  val mem =
    outward(Axi4).dFn(_ =>
      Right(AxiMasterPort(Vector(AxiMasterParams(name, IdRange(0, 1 << idBits), maxFlight = Some(maxFlight)))))
    )

def core(
  idBits:    Int,
  maxFlight: Int
)(
  using
  ws:        WrapperScope,
  name:      sourcecode.Name,
  file:      sourcecode.File,
  line:      sourcecode.Line
): CoreNodes =
  generator(Core)(new CoreNodes(name.value, idBits, maxFlight))

// ============ Dma: a bus-mastering DMA engine ============

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

// ============ Xbar: the n×m crossbar ============

val Xbar = new GeneratorEntry[XbarP]

/** An n×m AXI crossbar: every input reaches every output. The endpoint class declares the nodes, the full dependency
  * matrix, the id-remapping dFns and aggregating uFns, and the port-shape FullParam.
  */
final class AxiXbarNodes(
  name:        String,
  ins:         Vector[String],
  outs:        Vector[String],
  arbitration: Arbitration
)(
  using GeneratorScope[XbarP])
    extends Nodes:
  val clk             = inward(ClockDomain).uFn(_ => Right(()))
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
  arbitration: Arbitration
)(
  using
  ws:          WrapperScope,
  name:        sourcecode.Name,
  file:        sourcecode.File,
  line:        sourcecode.Line
): AxiXbarNodes =
  generator(Xbar)(new AxiXbarNodes(name.value, ins, outs, arbitration))

// ============ L2: a pass-through adapter with its own writeback master ============

val L2 = new GeneratorEntry[L2DeviceP]

/** The L2 slot ([[L2DeviceGen]], the real device — a register slice today): addresses and slave capabilities pass
  * through; downstream it reserves its writeback id range after the upstream id space (an adapter transforming Down).
  */
final class L2CacheNodes(
  capacityKiB: Int
)(
  using GeneratorScope[L2DeviceP])
    extends Nodes:
  val clk            = inward(ClockDomain).uFn(_ => Right(()))
  val in             = inward(Axi4)
  val out            = outward(Axi4)
  private val (d, u) = depend(in, out)
  out.dFn { ctx =>
    val up = ctx(d)
    Right(AxiMasterPort(up.masters :+ AxiMasterParams("l2.wb", IdRange(up.endId, up.endId + 1), maxFlight = Some(2))))
  }
  in.uFn(ctx => Right(ctx(u)))
  parameters(view => Right(L2DeviceP(capacityKiB, shapeOf(view, in), shapeOf(view, out))))

def l2Cache(
  capacityKiB: Int
)(
  using
  ws:          WrapperScope,
  name:        sourcecode.Name,
  file:        sourcecode.File,
  line:        sourcecode.Line
): L2CacheNodes =
  generator(L2)(new L2CacheNodes(capacityKiB))

// ============ DRAM: the uncached memory slave ============

val Dram = new GeneratorEntry[DramDeviceP]

/** The DRAM controller ([[DramDeviceGen]], the real device): one uncached address range on a 128-bit bus, named after
  * the instance; `wordsLog2` sizes the behavioral backing store.
  */
final class DramNodes(
  name:           String,
  ranks:          Int,
  wordsLog2:      Int,
  base:           Long,
  size:           Long,
  idCapacityBits: Int
)(
  using GeneratorScope[DramDeviceP])
    extends Nodes:
  val clk = inward(ClockDomain).uFn(_ => Right(()))
  parameters { view =>
    val s = shapeOf(view, in)
    Right(DramDeviceP(ranks, wordsLog2, s.addrBits, s.dataBits, s.idBits))
  }
  val in  = inward(Axi4).uFn(_ =>
    Right(
      AxiSlavePort(
        slaves = Vector(
          AxiSlaveParams(
            name,
            AddressSet.misaligned(base, size),
            RegionType.Uncached,
            executable = true,
            supportsWrite = TransferSizes(1, 64),
            supportsRead = TransferSizes(1, 64)
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
  wordsLog2:      Int,
  base:           Long,
  size:           Long,
  idCapacityBits: Int
)(
  using
  ws:             WrapperScope,
  name:           sourcecode.Name,
  file:           sourcecode.File,
  line:           sourcecode.Line
): DramNodes =
  generator(Dram)(new DramNodes(name.value, ranks, wordsLog2, base, size, idCapacityBits))

// ============ WidthBridge: wide to narrow ============

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

// ============ Uart: a memory-mapped peripheral ============

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

// ============ Gpio: a memory-mapped peripheral ============

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

// ============ GpioPads: the GPIO pin boundary ============

val GpioPadRing = new GeneratorEntry[GpioPadsP]

/** Terminates one GPIO pin bank; the FullParam records the settled width. */
final class GpioPadsNodes(
)(
  using GeneratorScope[GpioPadsP])
    extends Nodes:
  val in = inward(GpioPins).uFn(_ => Right(()))
  parameters(view => Right(GpioPadsP(view.edgeOf(in))))

def gpioPads(
)(
  using
  ws:   WrapperScope,
  name: sourcecode.Name,
  file: sourcecode.File,
  line: sourcecode.Line
): GpioPadsNodes =
  generator(GpioPadRing)(new GpioPadsNodes)

// ============ ClockSource: the clock and reset origin ============

val ClockSource = new GeneratorEntry[ClockSourceP]

/** One outward clock tap per name; taps are declared by name and looked up by name. */
final class ClockSourceNodes(
  freqHz: Int,
  taps:   Vector[String]
)(
  using GeneratorScope[ClockSourceP])
    extends Nodes:
  private val outs = taps.map { n =>
    given sourcecode.Name = sourcecode.Name(n)
    outward(ClockDomain).dFn(_ => Right(freqHz))
  }

  def tap(n: String): ClockDomain.Outward =
    require(taps.contains(n), s"clock source has no tap '$n' (taps: ${taps.mkString(", ")})")
    outs(taps.indexOf(n))

  parameters(_ => Right(ClockSourceP(freqHz, taps)))

def clockSource(
  freqHz: Int,
  taps:   Vector[String]
)(
  using
  ws:     WrapperScope,
  name:   sourcecode.Name,
  file:   sourcecode.File,
  line:   sourcecode.Line
): ClockSourceNodes =
  generator(ClockSource)(new ClockSourceNodes(freqHz, taps))

// ============ SerialPads: the serial pin boundary ============

val SerialPads = new GeneratorEntry[SerialPadsP]

/** Terminates one serial pin pair; the FullParam records the settled baud rate. */
final class SerialPadsNodes(
)(
  using GeneratorScope[SerialPadsP])
    extends Nodes:
  val in = inward(Serial).uFn(_ => Right(()))
  parameters(view => Right(SerialPadsP(view.edgeOf(in))))

def serialPads(
)(
  using
  ws:   WrapperScope,
  name: sourcecode.Name,
  file: sourcecode.File,
  line: sourcecode.Line
): SerialPadsNodes =
  generator(SerialPads)(new SerialPadsNodes)

/** Every registry entry bound to its zaozi generator — what the elaboration call receives. */
val axiBackends: Seq[GeneratorBackend] = Seq(
  ZaoziBackend(ClockSource, ClockSourceGen),
  ZaoziBackend(SerialPads, SerialPadsGen),
  ZaoziBackend(Core, CoreGen),
  ZaoziBackend(Dma, DmaDeviceGen),
  ZaoziBackend(Xbar, XbarGen),
  ZaoziBackend(L2, L2DeviceGen),
  ZaoziBackend(Dram, DramDeviceGen),
  ZaoziBackend(WidthBridge, BridgeDeviceGen),
  ZaoziBackend(Uart, UartDeviceGen),
  ZaoziBackend(Gpio, GpioDeviceGen),
  ZaoziBackend(GpioPadRing, GpioPadsGen)
)
