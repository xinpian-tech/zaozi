// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import com.vowstar.ditdah32.JtagInstruction
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

val Core = new GeneratorEntry[CoreDeviceP]

/** One core ([[CoreDeviceGen]], the real DitDah32 RV32EC behind a widening shim): a boundary outward node with a local
  * id space; the master is named after the instance.
  */
final class CoreNodes(
  name:        String,
  idBits:      Int,
  maxFlight:   Int,
  resetPc:     Int,
  enableDebug: Boolean,
  enableTrace: Boolean
)(
  using GeneratorScope[CoreDeviceP])
    extends Nodes:
  val clk               = inward(ClockDomain).uFn(_ => Right(()))
  private val debugNode = Option.when(enableDebug) {
    given sourcecode.Name = sourcecode.Name("debug")
    inward(DebugInterrupt).uFn(_ => Right(DebugHartCap(CoreDeviceP.xlen)))
  }

  /** The hart's debug port, present only on a core built with one. */
  def debug: DebugInterrupt.Inward =
    require(debugNode.isDefined, s"core '$name' was built without a debug node")
    debugNode.get

  /** The hart's instruction trace. A declaration, not a connection: the framework carries every leaf to the testbench
    * on its own, so nothing in the topology mentions it.
    */
  private val traceSource = Option.when(enableTrace) {
    given sourcecode.Name = sourcecode.Name("trace")
    dvSource(RvTrace)(RvTraceShape(CoreDeviceP.xlen, CoreDeviceP.regIndexBits), traceLayer)
  }

  parameters { view =>
    val s = shapeOf(view, mem)
    Right(CoreDeviceP(resetPc, s.addrBits, s.dataBits, s.idBits, enableDebug, enableTrace))
  }
  val mem =
    outward(Axi4).dFn(_ =>
      Right(AxiMasterPort(Vector(AxiMasterParams(name, IdRange(0, 1 << idBits), maxFlight = Some(maxFlight)))))
    )

def core(
  idBits:      Int,
  maxFlight:   Int,
  resetPc:     Int,
  enableDebug: Boolean,
  enableTrace: Boolean
)(
  using
  ws:          WrapperScope,
  name:        sourcecode.Name,
  file:        sourcecode.File,
  line:        sourcecode.Line
): CoreNodes =
  generator(Core)(new CoreNodes(name.value, idBits, maxFlight, resetPc, enableDebug, enableTrace))

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
        outs.zip(outputs).map((n, b) => n -> shapeOf(view, b)),
        outputs.map(b => view.edgeOf(b).slave.slaves.flatMap(_.address).map(a => (a.base, a.mask)))
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

// ============ DRAM: the uncached memory slave ============

val Dram = new GeneratorEntry[DramDeviceP]

/** The DRAM controller ([[DramDeviceGen]], the real device): one uncached executable address range on a 128-bit bus,
  * named after the instance; `wordsLog2` sizes the behavioral backing store. The cores reset into it and a debugger
  * loads their program there.
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

// ============ Pll: the chip's clock ============

val Pll = new GeneratorEntry[PllP]

/** The PLL ([[PllGen]]): one reference clock in from the board, `outHz` out to every consumer on the die. The loop
  * ratio comes out of the settled reference frequency, and a ratio the loop cannot lock fails here.
  */
final class PllNodes(
  name:  String,
  outHz: Int,
  taps:  Vector[String]
)(
  using GeneratorScope[PllP])
    extends Nodes:
  val ref          = inward(ClockDomain).uFn(_ => Right(()))
  private val outs = taps.map { n =>
    given sourcecode.Name = sourcecode.Name(n)
    outward(ClockDomain).dFn(_ => Right(outHz))
  }

  /** Clock taps are declared by name, so they are looked up by name. */
  def tap(n: String): ClockDomain.Outward =
    require(taps.contains(n), s"pll '$name' has no clock tap '$n' (taps: ${taps.mkString(", ")})")
    outs(taps.indexOf(n))

  parameters { view =>
    val refHz = view.edgeOf(ref)
    val ratio = BigInt(outHz).gcd(BigInt(refHz)).toInt
    val mult  = outHz / ratio
    val div   = refHz / ratio
    if mult > PllNodes.maxMult || div > PllNodes.maxDiv then
      Left(
        Violation(
          s"$refHz Hz to $outHz Hz needs a $mult/$div loop, beyond the PLL's ${PllNodes.maxMult}/${PllNodes.maxDiv}"
        )
      )
    else Right(PllP(refHz, outHz, mult, div, taps))
  }

object PllNodes:
  /** What the loop's dividers can actually reach. */
  val maxMult: Int = 64
  val maxDiv:  Int = 8

def pll(
  outHz: Int,
  taps:  Vector[String]
)(
  using
  ws:    WrapperScope,
  name:  sourcecode.Name,
  file:  sourcecode.File,
  line:  sourcecode.Line
): PllNodes =
  generator(Pll)(new PllNodes(name.value, outHz, taps))

// ============ Dtm: the debug transport ============

val Dtm = new GeneratorEntry[DtmP]

/** The JTAG debug transport ([[DtmGen]]): the TAP pins downward to whoever drives them, the DMI bus onward to the debug
  * module. `abits` is the transport's own scan-register width — negotiation checks the debug module against it.
  */
final class DtmNodes(
  name:   String,
  idcode: Long,
  abits:  Int
)(
  using GeneratorScope[DtmP])
    extends Nodes:
  val clk  = inward(ClockDomain).uFn(_ => Right(()))
  val jtag =
    outward(Jtag).dFn(_ => Right(JtagTap(idcode, DtmNodes.irLength, abits, 32, JtagInstruction.DMI)))
  val dmi  = outward(Dmi).dFn(_ => Right(DmiMaster(name, abits, 32)))
  parameters { view =>
    val e = view.edgeOf(dmi)
    Right(DtmP(idcode, DtmNodes.irLength, e.abits, e.dataBits))
  }

object DtmNodes:
  /** The TAP's instruction register width, fixed by the transport's hardware. */
  val irLength: Int = 5

def debugTransport(
  idcode: Long,
  abits:  Int
)(
  using
  ws:     WrapperScope,
  name:   sourcecode.Name,
  file:   sourcecode.File,
  line:   sourcecode.Line
): DtmNodes =
  generator(Dtm)(new DtmNodes(name.value, idcode, abits))

// ============ Dm: the debug module ============

val Dm = new GeneratorEntry[DmP]

/** The debug module ([[DmGen]]): a DMI slave with one outward port per hart. Every hart it holds is one negotiated
  * edge, so the hart count is the topology's, not a parameter to keep in sync.
  */
final class DmNodes(
  name:        String,
  harts:       Int,
  haltOnReset: Boolean
)(
  using GeneratorScope[DmP])
    extends Nodes:
  val clk               = inward(ClockDomain).uFn(_ => Right(()))
  val dmi               = inward(Dmi).uFn(_ => Right(DmiSlave(name, DmNodes.addrBits, 32)))
  private val hartPorts = (0 until harts).map { i =>
    given sourcecode.Name = sourcecode.Name(s"hart$i")
    outward(DebugInterrupt).dFn(_ => Right(DebugRequest(i)))
  }

  /** Hart ports are indexed by the hart id the module assigns them. */
  def hart(i: Int): DebugInterrupt.Outward =
    require(i >= 0 && i < harts, s"debug module '$name' has no hart $i (holds $harts)")
    hartPorts(i)

  parameters { view =>
    val e     = view.edgeOf(dmi)
    val xlens = hartPorts.map(view.edgeOf(_).xlen).distinct
    if xlens.sizeIs != 1 then Left(Violation(s"harts disagree on register width: ${xlens.mkString(", ")}"))
    else if xlens.head != e.dataBits then
      Left(Violation(s"hart register width ${xlens.head} does not match the ${e.dataBits}-bit abstract data path"))
    else Right(DmP(harts, e.abits, e.dataBits, xlens.head, haltOnReset))
  }

object DmNodes:
  /** The debug register file's address width (`haltsum0` at 0x40 is the highest register it answers). */
  val addrBits: Int = 7

def debugModule(
  harts:       Int,
  haltOnReset: Boolean
)(
  using
  ws:          WrapperScope,
  name:        sourcecode.Name,
  file:        sourcecode.File,
  line:        sourcecode.Line
): DmNodes =
  generator(Dm)(new DmNodes(name.value, harts, haltOnReset))

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

// ============ TestHarness: everything outside the chip ============

val TestHarness = new GeneratorEntry[TestHarnessP]

/** The design's testbench ([[TestHarnessGen]]): it publishes the clock the chip runs on, drives the JTAG pins, and
  * terminates the serial and GPIO pins. Every rate it needs comes from the settled edges — the baud rate from the
  * serial edge, the pin count from the GPIO edge, the TAP from the JTAG edge — so nothing here is stated twice.
  */
final class TestHarnessNodes(
  freqHz: Int,
  taps:   Vector[String],
  script: Vector[DmiWrite],
  tckDiv: Int,
  dwell:  Int
)(
  using GeneratorScope[TestHarnessP])
    extends Nodes:
  private val outs = taps.map { n =>
    given sourcecode.Name = sourcecode.Name(n)
    outward(ClockDomain).dFn(_ => Right(freqHz))
  }

  /** Clock taps are declared by name, so they are looked up by name. */
  def tap(n: String): ClockDomain.Outward =
    require(taps.contains(n), s"harness has no clock tap '$n' (taps: ${taps.mkString(", ")})")
    outs(taps.indexOf(n))

  val serialPins = inward(Serial).uFn(_ => Right(()))
  val gpioPins   = inward(GpioPins).uFn(_ => Right(()))
  val jtagPins   = inward(Jtag).uFn(_ => Right(()))
  val traceClock = inward(ClockDomain).uFn(_ => Right(()))

  parameters { view =>
    val t      = view.edgeOf(jtagPins)
    // The probe manifest is the design's, complete regardless of declaration order: one source per hart, one leaf per
    // trace signal, each already named with the port the framework will hand it over.
    val traces = view.probes.map { src =>
      val shape = upickle.default.read[RvTraceShape](src.down)
      TraceSource(
        src.id.module.path.last,
        shape.xlen,
        shape.regIndexBits,
        src.leaves.map { l =>
          val (width, bool) = l.tpe match
            case ProtocolInterface.Bool    => (1, true)
            case ProtocolInterface.UInt(w) => (w, false)
            case other                     =>
              throw new IllegalArgumentException(s"trace leaf ${l.portName} is $other, not an integer")
          TracePort(l.path.nameSegments.last, l.portName, width, bool)
        }
      )
    }
    Right(
      TestHarnessP(
        freqHz,
        taps,
        view.edgeOf(serialPins),
        view.edgeOf(gpioPins),
        script,
        t.irLength,
        t.abits,
        t.dataBits,
        t.dmiInstruction,
        tckDiv,
        dwell,
        traces
      )
    )
  }

def testHarness(
  freqHz: Int,
  taps:   Vector[String],
  script: Vector[DmiWrite],
  tckDiv: Int,
  dwell:  Int
)(
  using
  ws:     WrapperScope,
  name:   sourcecode.Name,
  file:   sourcecode.File,
  line:   sourcecode.Line
): TestHarnessNodes =
  testbench(TestHarness)(new TestHarnessNodes(freqHz, taps, script, tckDiv, dwell))

// ============ UartHarness: outside the chip, for the UART demo ============

val UartHarness = new GeneratorEntry[UartHarnessP]

/** The UART demo's testbench ([[UartHarnessGen]]): the clock, and the loopback jumper on the serial pins. */
final class UartHarnessNodes(
  freqHz: Int,
  taps:   Vector[String]
)(
  using GeneratorScope[UartHarnessP])
    extends Nodes:
  private val outs = taps.map { n =>
    given sourcecode.Name = sourcecode.Name(n)
    outward(ClockDomain).dFn(_ => Right(freqHz))
  }

  def tap(n: String): ClockDomain.Outward =
    require(taps.contains(n), s"harness has no clock tap '$n' (taps: ${taps.mkString(", ")})")
    outs(taps.indexOf(n))

  val serialPins = inward(Serial).uFn(_ => Right(()))

  parameters(view => Right(UartHarnessP(freqHz, taps, view.edgeOf(serialPins))))

def uartHarness(
  freqHz: Int,
  taps:   Vector[String]
)(
  using
  ws:     WrapperScope,
  name:   sourcecode.Name,
  file:   sourcecode.File,
  line:   sourcecode.Line
): UartHarnessNodes =
  testbench(UartHarness)(new UartHarnessNodes(freqHz, taps))

/** Every registry entry bound to its zaozi generator — what the elaboration call receives. */
val axiBackends: Seq[GeneratorBackend] = Seq(
  ZaoziBackend(TestHarness, TestHarnessGen),
  ZaoziBackend(Pll, PllGen),
  ZaoziBackend(Core, CoreDeviceGen),
  ZaoziBackend(Dma, DmaDeviceGen),
  ZaoziBackend(Xbar, XbarGen),
  ZaoziBackend(Dtm, DtmGen),
  ZaoziBackend(Dm, DmGen),
  ZaoziBackend(Dram, DramDeviceGen),
  ZaoziBackend(WidthBridge, BridgeDeviceGen),
  ZaoziBackend(Uart, UartDeviceGen),
  ZaoziBackend(Gpio, GpioDeviceGen)
)
