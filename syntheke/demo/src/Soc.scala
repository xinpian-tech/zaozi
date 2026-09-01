// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.axi.Axi4
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}

/** The demo SoC: the design document's motivation SoC, assembled from the IP library in [[AxiLibrary]] and negotiated
  * over [[me.jiuyang.syntheke.demo.axi.Axi4]]. This file is the SoC integrator's side — instantiate and wire; what each
  * IP offers and demands is the library's, and what the design becomes is negotiation's.
  *
  * Nothing here is a test, and nothing here says how the result is started — that is [[Bringup]]'s. The design is
  * elaborated by [[Main]] into artifacts, and what runs them is meson's business, one directory up.
  */
object Soc:

  /** The address map, in one place. Every IP below is placed from these, and the debugger is told about them out of the
    * settled edges rather than from here.
    */
  val loadBase:      Long = 0x80000000L // DRAM: where the harts run from and the debugger puts the program
  val dramBytes:     Long = 0x40000000L // one rank of DDR4 8Gb x8, the device the harness's model is configured as
  val uartBase:      Long = 0x10000000L
  val gpioBase:      Long = 0x10010000L
  val periphSize:    Long = 0x1000L
  // The DMA walks a window inside DRAM, which is why its base is derived from the memory's rather than restated.
  val dmaWindowLog2: Int  = 10
  val dmaTarget:     Long = loadBase + 0x800

  /** The TCP port the simulation's JTAG bridge listens on. A property of the harness, which is why it is here; the
    * debugger reads it back out of [[Bringup]].
    */
  val jtagPort: Int = 5555

  /** The Ramulator configuration, as the memory model's parameter names it and the simulation finds it. */
  val dramConfigFile: String = "dram.yaml"

  /** What leaves the debug island. Named rather than a tuple: two of these are clocks of the same type, and a tuple
    * would let them be swapped without a word from the compiler or the negotiation.
    */
  final case class DebugIsland(
    jtag:   Jtag.Outward,
    dtmClk: ClockDomain.Inward,
    dmClk:  ClockDomain.Inward,
    sb:     Axi4.Outward,
    hart0:  DebugInterrupt.Outward,
    hart1:  DebugInterrupt.Outward)

  /** The topology: two harts on a crossbar with a DMA, a width bridge to the peripherals, a debug island of its own,
    * and everything that is not the chip in the test harness — the clock, the debug adapter on the JTAG pins, the DRAM
    * on the memory port, the console on the serial pins and the board the GPIO pads are wired to. FullParam = the zaozi
    * parameter of each IP. No L2: an AXI fabric without coherence gives one nothing testable to do.
    *
    * The clock plumbing is one tap per consumer because a node takes part in exactly one bind — see the note on
    * [[me.jiuyang.syntheke.outward]]. A clock source therefore declares who it feeds, and this is what that costs.
    */
  def build(refHz: Int = 25000000): DesignSpec =
    Design {
      // Outside the chip: the crystal, the harness's own devices running off it, and the memory the chip's bus port
      // ends in — one rank of DDR4, which is a device to attach, not RTL to write.
      val harness = testHarness(
        freqHz = refHz,
        taps = Vector("ref"),
        jtagPort = jtagPort,
        tckDiv = 2,
        memBase = loadBase,
        memSize = dramBytes,
        memIdCapBits = 6,
        dramConfig = dramConfigFile
      )

      // On the die: the PLL multiplies the reference to the system clock and feeds every consumer.
      val sysPll = pll(
        outHz = 100000000,
        taps = Vector(
          "core0",
          "core1",
          "dma",
          "sysXbar",
          "mem",
          "bridge",
          "periphXbar",
          "uart",
          "gpio",
          "dtm",
          "dm",
          "trace"
        )
      )
      sysPll.ref <-- harness.tap("ref")

      // The harts halt out of reset and the debugger hands them their PC, so the reset vector is never fetched.
      val core0 = core(idBits = 2, maxFlight = 4, resetPc = 0, enableDebug = true, enableTrace = true)
      val core1 = core(idBits = 3, maxFlight = 8, resetPc = 0, enableDebug = true, enableTrace = true)
      val dma   = dmaCtrl(idBits = 1, maxFlight = 1, targetBase = dmaTarget, windowLog2 = dmaWindowLog2)

      // Crossbar ports are named after what they carry, so the wiring below reads as what it is.
      val sysXbar = axiXbar(
        Vector("core0", "core1", "dma", "debug"),
        Vector("mem", "periph"),
        Arbitration.RoundRobin
      )

      val bridge = widthBridge(wideBeatBytes = 16, maxUpstreamTransfer = 64)

      val periphXbar = axiXbar(Vector("bridge"), Vector("uart", "gpio"), Arbitration.FixedPriority)

      val uart = uartCtrl(base = uartBase, size = periphSize, idCapacityBits = 8, baud = 115200)
      val gpio = gpioCtrl(base = gpioBase, size = periphSize, idCapacityBits = 8, width = 8)

      // The on-chip half of the debug chain is one wrapper module: transport and debug module inside it, and only
      // what leaves the island crossing its boundary — the pins, one port per hart, and the module's bus master,
      // which is the path a debugger's download takes.
      val debug = wrapper {
        val dtm = debugTransport(idcode = 0xdeadbeb1L, abits = 7)
        val dm  = debugModule(harts = 2, haltOnReset = true, sbIdBits = 1)
        dm.dmi <-- dtm.dmi
        DebugIsland(dtm.jtag, dtm.clk, dm.clk, dm.sb, dm.hart(0), dm.hart(1))
      }

      sysXbar.input("core0") <-- core0.mem
      sysXbar.input("core1") <-- core1.mem
      sysXbar.input("dma") <-- dma.mem
      sysXbar.input("debug") <-- debug.sb
      bridge.in <-- sysXbar.output("periph")
      periphXbar.input("bridge") <-- bridge.out
      uart.in <-- periphXbar.output("uart")
      gpio.in <-- periphXbar.output("gpio")

      core0.debug <-- debug.hart0
      core1.debug <-- debug.hart1

      // The chip's pins, all terminating in the harness — the memory port among them.
      harness.serialPins <-- uart.serial
      harness.gpioPins <-- gpio.pins
      harness.jtagPins <-- debug.jtag
      harness.memPins <-- sysXbar.output("mem")
      harness.memClock <-- sysPll.tap("mem")
      harness.traceClock <-- sysPll.tap("trace")

      core0.clk <-- sysPll.tap("core0")
      core1.clk <-- sysPll.tap("core1")
      dma.clk <-- sysPll.tap("dma")
      sysXbar.clk <-- sysPll.tap("sysXbar")
      bridge.clk <-- sysPll.tap("bridge")
      periphXbar.clk <-- sysPll.tap("periphXbar")
      uart.clk <-- sysPll.tap("uart")
      gpio.clk <-- sysPll.tap("gpio")
      debug.dtmClk <-- sysPll.tap("dtm")
      debug.dmClk <-- sysPll.tap("dm")
    }
