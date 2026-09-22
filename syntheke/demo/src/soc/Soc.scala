package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.stdlib.iomux.IOMuxRoute

object Soc:

  final case class DebugIsland(
    jtag:     Jtag.Outward,
    tckClk:   ClockReset.Inward,
    crossTck: ClockReset.Inward,
    crossSys: ClockReset.Inward,
    dmClk:    ClockReset.Inward,
    sba:      Axi4.Outward,
    hart0:    DebugInterrupt.Outward,
    hart1:    DebugInterrupt.Outward)

  final case class Ports(
    ref: InwardBoundary[ClockReset.type],
    dtmClock: InwardBoundary[ClockReset.type],
    crossingClock: InwardBoundary[ClockReset.type],
    memory: OutwardBoundary[Axi4.type],
    memoryClock: OutwardBoundary[ClockReset.type],
    pins: Vector[OutwardBoundary[IO.type]])

  def build(config: SocConfig): Design[Ports] =
    import config.*
    Design("Soc") {
      val refClock = ClockDomain.declare(ClockValue(refHz), None)
      val tckClock = ClockDomain.declare(ClockValue(refHz / (2 * tckDiv)), None)
      val boardReset = ResetDomain.declare(ResetValue(true, ResetAssertion.Asynchronous, ResetRelease.Synchronous), None)

      val alwaysOnPowerDomain = PowerDomain.declare(
        PowerValue.Supply(millivolts = 900, alwaysOn = true),
        Some(PowerRequirement(minMillivolts = Some(850), maxMillivolts = Some(950), requiresAlwaysOn = true))
      )
      val cpu0PowerDomain     = PowerDomain.declare(
        PowerValue.Supply(millivolts = 850, alwaysOn = false),
        None
      )
      val cpu1PowerDomain     = PowerDomain.declare(
        PowerValue.Supply(millivolts = 950, alwaysOn = false),
        None
      )
      alwaysOnPowerDomain.provide:

        val sysPll = pll(
          outHz = sysHz,
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
            "power",
            "iomux",
            "dmiCross",
            "dm"
          )
        )
        val ref = sysPll.ref.boundary(())(refClock, boardReset, PowerDomain)

        val core0        = cpu0PowerDomain.provide {
          core(idBits = 2, maxFlight = 4, resetPc = 0, enableDebug = true, enableTrace = true)
        }
        val core1        = cpu1PowerDomain.provide {
          core(idBits = 3, maxFlight = 8, resetPc = 0, enableDebug = true, enableTrace = true)
        }
        val cpu0Boundary = cpuPowerBoundary(cpu0PowerDomain, startupCycles = 8)
        val cpu1Boundary = cpuPowerBoundary(cpu1PowerDomain, startupCycles = 16)
        val dma          = dmaCtrl(idBits = 1, maxFlight = 1, targetBase = dmaTarget, windowLog2 = dmaWindowLog2)

        val sysXbar = axiXbar(
          Vector("core0", "core1", "dma", "debug"),
          Vector("mem", "periph"),
          Arbitration.RoundRobin
        )

        val bridge = widthBridge(wideBeatBytes = 16)

        val periphXbar = axiXbar(Vector("bridge"), Vector("uart", "gpio", "power", "iomux"), Arbitration.FixedPriority)
        val power      = powerController(base = powerBase, size = periphSize, idCapacityBits = 8)

        val uart      = uartCtrl(base = uartBase, size = periphSize, idCapacityBits = 8, baud = baud)
        val uartClock = clockBuffer()
        val gpio      = gpioCtrl(base = gpioBase, size = periphSize, idCapacityBits = 8, width = gpioWidth)
        val uartIO = serialIO(uart.serial)
        val gpioIOs = gpioIO(gpio.pins, gpioWidth)
        val alternatePins = (uartPins ++ jtagPins).toSet
        val mux = ioMux(
          base = iomuxBase,
          size = periphSize,
          idCapacityBits = 8,
          pinCount = pinCount,
          routes = Vector("uartTx", "uartRx").zip(uartPins).map((name, pin) => name -> IOMuxRoute(pin, 0)) ++
            Vector("jtagTms", "jtagTdi", "jtagTrstN", "jtagTdo").zip(jtagPins)
              .map((name, pin) => name -> IOMuxRoute(pin, 0)) ++
            Vector.tabulate(gpioWidth)(pin => s"gpio$pin" -> IOMuxRoute(pin, if alternatePins(pin) then 1 else 0))
        )

        val debug = wrapper("DebugIsland") {
          val dtm   = debugTransport(idcode = 0xdeadbeb1L, abits = 7)
          val cross = dmiCrossing(depth = 4)
          val dm    = debugModule(harts = 2, haltOnReset = true, sbIdBits = 1)
          cross.in <-- dtm.dmi
          dm.dmi <-- cross.out
          (
            DebugIsland(dtm.jtag, dtm.tck, cross.enqClk, cross.deqClk, dm.clk, dm.sb, dm.hart(0), dm.hart(1)),
            Vector.empty
          )
        }
        val jtagPads = jtagIO(debug.jtag)

        cpu0Boundary.cpuMem <-- core0.mem
        cpu1Boundary.cpuMem <-- core1.mem
        sysXbar.input("core0") <-- cpu0Boundary.bus
        sysXbar.input("core1") <-- cpu1Boundary.bus
        sysXbar.input("dma") <-- dma.mem
        sysXbar.input("debug") <-- debug.sba
        bridge.in <-- sysXbar.output("periph")
        periphXbar.input("bridge") <-- bridge.out
        uart.in <-- periphXbar.output("uart")
        gpio.in <-- periphXbar.output("gpio")
        power.in <-- periphXbar.output("power")
        mux.in <-- periphXbar.output("iomux")
        cpu0Boundary.control <-- power.cpu0
        cpu1Boundary.control <-- power.cpu1

        cpu0Boundary.debug <-- debug.hart0
        cpu1Boundary.debug <-- debug.hart1
        core0.debug <-- cpu0Boundary.cpuDebug
        core1.debug <-- cpu1Boundary.cpuDebug

        val memory = sysXbar.output("mem").boundary(AxiSlavePort(
          slaves = Vector(AxiSlaveParams("dram", AddressSet.misaligned(loadBase, dramBytes),
            RegionType.Uncached, executable = true, supportsWrite = TransferSizes(1, 64), supportsRead = TransferSizes(1, 64))),
          beatBytes = 16, idCapacityBits = 6, minLatency = 8
        ))(sysPll.systemClockDomain, sysPll.systemResetDomain, PowerDomain)
        val memoryClock = sysPll.tap("mem").boundary(())(ClockDomain, ResetDomain, PowerDomain)

        mux.input("uartTx") <-- uartIO.tx
        mux.input("uartRx") <-- uartIO.rx
        mux.input("jtagTms") <-- jtagPads.tms
        mux.input("jtagTdi") <-- jtagPads.tdi
        mux.input("jtagTrstN") <-- jtagPads.trstN
        mux.input("jtagTdo") <-- jtagPads.tdo
        gpioIOs.zipWithIndex.foreach((pin, i) => mux.input(s"gpio$i") <-- pin)
        val pins = mux.pads.zipWithIndex.map { (pad, i) =>
          given sourcecode.Name = sourcecode.Name(s"pin$i")
          pad.boundary(())(PowerDomain)
        }
        val dtmClock = debug.tckClk.boundary(())(tckClock, boardReset, PowerDomain)
        val crossingClock = debug.crossTck.boundary(())(tckClock, boardReset, PowerDomain)

        cpu0Boundary.clk <-- sysPll.tap("core0")
        cpu1Boundary.clk <-- sysPll.tap("core1")
        core0.clk <-- cpu0Boundary.cpuClk
        core1.clk <-- cpu1Boundary.cpuClk
        core0.retention <-- cpu0Boundary.retention
        core1.retention <-- cpu1Boundary.retention
        dma.clk <-- sysPll.tap("dma")
        sysXbar.clk <-- sysPll.tap("sysXbar")
        bridge.clk <-- sysPll.tap("bridge")
        periphXbar.clk <-- sysPll.tap("periphXbar")
        uartClock.in <-- sysPll.tap("uart")
        uart.clk <-- uartClock.out
        gpio.clk <-- sysPll.tap("gpio")
        power.clk <-- sysPll.tap("power")
        mux.clk <-- sysPll.tap("iomux")
        debug.crossSys <-- sysPll.tap("dmiCross")
        debug.dmClk <-- sysPll.tap("dm")
        (Ports(ref, dtmClock, crossingClock, memory, memoryClock, pins), Vector.empty)
    }
