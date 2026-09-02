// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.harness

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.{
  shapeOf,
  AddressSet,
  Axi4,
  AxiSlaveParams,
  AxiSlavePort,
  ClockDomain,
  GpioPins,
  Jtag,
  RegionType,
  RvTraceShape,
  Serial,
  TransferSizes
}
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}

/** The syntheke wrap of the design's testbench — the same shape as an IP's wrap in `node/`, and deliberately not there:
  * the harness is not an IP of this SoC. Nothing here is on the die, so nothing here is something the SoC ships; it is
  * the board, the debugger's adapter, the terminal and the DRAM, in one module the framework knows as the testbench
  * (`testbench`, at most one per design).
  *
  * Its zaozi modules are the rest of this package rather than `zaoziimpl/`: the harness is cut out of the design by
  * what it is, not by which layer it belongs to, so its wrap and its modules live together.
  */

val TestHarness = new GeneratorEntry[TestHarnessP]

/** The design's testbench ([[TestHarnessGen]]): it publishes the clock the chip runs on, holds the debug adapter on the
  * JTAG pins, terminates the serial and GPIO pins, and is where the chip's memory port ends. Every rate it needs comes
  * from the settled edges — the baud rate from the serial edge, the pin count from the GPIO edge — so nothing here is
  * stated twice. The TAP's own parameters are no longer among them: the adapter is a wire to a real debugger, and it is
  * the debugger that knows the protocol.
  *
  * The memory is a node like any other: the DRAM is not on the die, so it is not an IP of this design. What the harness
  * publishes upward is the range it answers for, which is the device Ramulator models on the other side of `memPins`.
  */
final class TestHarnessNodes(
  name:         String,
  freqHz:       Int,
  taps:         Vector[String],
  jtagPort:     Int,
  tckDiv:       Int,
  memBase:      Long,
  memSize:      Long,
  memIdCapBits: Int,
  dramConfig:   String
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
  val memPins    = inward(Axi4).uFn(_ =>
    Right(
      AxiSlavePort(
        slaves = Vector(
          AxiSlaveParams(
            name,
            AddressSet.misaligned(memBase, memSize),
            RegionType.Uncached,
            executable = true,
            supportsWrite = TransferSizes(1, 64),
            supportsRead = TransferSizes(1, 64)
          )
        ),
        beatBytes = 16,
        idCapacityBits = memIdCapBits,
        minLatency = 8
      )
    )
  )
  val memClock   = inward(ClockDomain).uFn(_ => Right(()))
  val traceClock = inward(ClockDomain).uFn(_ => Right(()))

  parameters { view =>
    // The JTAG edge is not read here: the pins are taken as they come, and what rides them is the debugger's business.
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
        jtagPort,
        tckDiv,
        shapeOf(view, memPins),
        memBase,
        view.edgeOf(memClock),
        dramConfig,
        traces
      )
    )
  }

def testHarness(
  freqHz:       Int,
  taps:         Vector[String],
  jtagPort:     Int,
  tckDiv:       Int,
  memBase:      Long,
  memSize:      Long,
  memIdCapBits: Int,
  dramConfig:   String
)(
  using
  ws:           WrapperScope,
  name:         sourcecode.Name,
  file:         sourcecode.File,
  line:         sourcecode.Line
): TestHarnessNodes =
  testbench(TestHarness)(
    new TestHarnessNodes(name.value, freqHz, taps, jtagPort, tckDiv, memBase, memSize, memIdCapBits, dramConfig)
  )
