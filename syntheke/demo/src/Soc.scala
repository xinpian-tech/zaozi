// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.axi.Axi4
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}

/** The demo SoC: the design document's motivation SoC, assembled from the IP library in [[AxiLibrary]] and negotiated
  * over [[me.jiuyang.syntheke.demo.axi.Axi4]]. This file is the SoC integrator's side — instantiate and wire; what each
  * IP offers and demands is the library's.
  *
  * Nothing here is a test. The design is elaborated by [[Main]] into artifacts, and what runs them end to end — the
  * simulator, the debugger, the memory — is meson's business, one directory up.
  */
object Soc:

  val loadBase:  Long = 0x80000000L // the DRAM base: where the debugger puts the program
  val dramBytes: Long = 0x40000000L // one rank of DDR4 8Gb x8, the device the harness's model is configured as
  // The two harts get different work, so the printed line proves both halves of the debug module's hart array: hart 0
  // parks in the done-spin, hart 1 is the one that runs the program.
  val hart0Pc:   Long = loadBase + 0x2c
  val hart1Pc:   Long = loadBase

  /** The program, hand-assembled RV32E (registers x5–x8): walk the string at +0x40 and write each byte to the UART's
    * TXDATA, polling STATUS bit0 (txBusy) between bytes; a NUL ends the walk at the spin at +0x2c, where hart 1 parks.
    *
    * {{{
    * 00: 100002B7  lui  x5, 0x10000    ; x5 = UART base
    * 04: 80000337  lui  x6, 0x80000    ; x6 = DRAM base
    * 08: 04030313  addi x6, x6, 0x40   ; x6 = &"hello world\n"
    * 0c: 00030383  lb   x7, 0(x6)      ; next char
    * 10: 00038E63  beq  x7, x0, +0x1c  ; NUL -> 2c
    * 14: 0082A403  lw   x8, 8(x5)      ; STATUS
    * 18: 00147413  andi x8, x8, 1      ; txBusy
    * 1c: FE041CE3  bne  x8, x0, -8     ; busy -> 14
    * 20: 0072A023  sw   x7, 0(x5)      ; TXDATA = char
    * 24: 00130313  addi x6, x6, 1
    * 28: FE5FF06F  jal  x0, -0x1c      ; -> 0c
    * 2c: 0000006F  jal  x0, 0          ; done: spin (where hart 0 parks)
    * 40: "hello world\n\0"
    * }}}
    */
  val program: Vector[Long] = Vector(
    0x100002b7L, 0x80000337L, 0x04030313L, 0x00030383L, 0x00038e63L, 0x0082a403L, 0x00147413L, 0xfe041ce3L, 0x0072a023L,
    0x00130313L, 0xfe5ff06fL, 0x0000006fL, 0L, 0L, 0L, 0L, 0x6c6c6568L, 0x6f77206fL, 0x0a646c72L, 0L
  )

  /** The TCP port the simulation's JTAG bridge listens on, and the debugger connects to. */
  val jtagPort: Int    = 5555
  val chipName: String = "syntheke-demo"

  /** The Ramulator configuration, as the model's parameter names it and the simulation finds it. */
  val dramConfigFile: String = "dram.yaml"

  /** The program as the debugger downloads it: little-endian words, which is how memory holds them. */
  def programImage: Array[Byte] =
    program.flatMap(w => (0 until 4).map(b => ((w >> (b * 8)) & 0xff).toByte)).toArray

  /** What the bring-up script needs from the design it is about to run: where the debugger attaches, what it downloads
    * where, and what each hart should then be seen doing. The script reads this instead of repeating any of it.
    */
  def bringupEnv: String =
    f"""JTAG_BRIDGE=127.0.0.1:$jtagPort
       |CHIP=$chipName
       |LOAD=0x${loadBase.toHexString}
       |HART0_PC=0x${hart0Pc.toHexString}
       |HART1_PC=0x${hart1Pc.toHexString}
       |HART1_FIRST='${hart1Pc}%08x: ${program.head}%08x'
       |""".stripMargin

  /** The target description probe-rs needs, read out of the settled design: the TAP it will find on the pins, the harts
    * the debug module holds, the memory the fabric decodes to RAM. Nothing here is stated twice — every number comes
    * from an edge the negotiation settled, which is the same thing the RTL was built from.
    */
  def probeRsTarget(resolved: ResolvedDesign): String =
    val root  = ModuleId.root
    val tap   = resolved.edgeAt(ModuleNodeId(root / "harness", "jtagPins")).edgeAs(Jtag)
    val harts = resolved.generatorModule(root / "debug" / "dm").get.fullParam.asInstanceOf[DmP].harts
    val ram   = resolved.edgeAt(ModuleNodeId(root / "harness", "memPins")).edgeAs(Axi4).slave.slaves.head.address.head
    val cores = (0 until harts)
      .map(i => s"""      - name: hart$i
                   |        type: riscv
                   |        core_access_options: !Riscv
                   |          hart_id: $i""".stripMargin)
      .mkString("\n")
    s"""name: syntheke
       |variants:
       |  - name: $chipName
       |    cores:
       |$cores
       |    memory_map:
       |      - !Ram
       |        name: dram
       |        range:
       |          start: 0x${ram.base.toHexString}
       |          end: 0x${(ram.base + ram.mask + 1).toHexString}
       |        cores: [${(0 until harts).map(i => s"hart$i").mkString(", ")}]
       |    jtag:
       |      scan_chain:
       |        - name: dtm
       |          ir_len: ${tap.irLength}
       |      force_scan_chain: true
       |""".stripMargin

  /** The topology: two harts on a crossbar with a DMA, a width bridge to the peripherals, a debug island of its own,
    * and everything that is not the chip in the test harness — the clock, the debug adapter on the JTAG pins, the DRAM
    * on the memory port, the console on the serial pins and the board the GPIO pads are wired to. FullParam = the zaozi
    * parameter of each IP. No L2: an AXI fabric without coherence gives one nothing testable to do.
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
      val dma   = dmaCtrl(idBits = 1, maxFlight = 1, targetBase = 0x80000800L, windowLog2 = 10)

      val sysXbar = axiXbar(Vector("in0", "in1", "in2", "in3"), Vector("mem", "periph"), Arbitration.RoundRobin)

      val bridge = widthBridge(wideBeatBytes = 16, maxUpstreamTransfer = 64)

      val periphXbar = axiXbar(Vector("in"), Vector("uart", "gpio"), Arbitration.FixedPriority)

      val uart = uartCtrl(0x10000000L, 0x1000L, idCapacityBits = 8, baud = 115200)
      val gpio = gpioCtrl(0x10010000L, 0x1000L, idCapacityBits = 8, width = 8)

      // The on-chip half of the debug chain is one wrapper module: transport and debug module inside it, and only
      // what leaves the island crossing its boundary — the pins, one port per hart, and the module's bus master,
      // which is the path a debugger's download takes.
      val debug                                            = wrapper {
        val dtm = debugTransport(idcode = 0xdeadbeb1L, abits = 7)
        val dm  = debugModule(harts = 2, haltOnReset = true, sbIdBits = 1)
        dm.dmi <-- dtm.dmi
        (dtm.jtag, dtm.clk, dm.clk, dm.sb, dm.hart(0), dm.hart(1))
      }
      val (jtagPins, dtmClk, dmClk, debugSb, hart0, hart1) = debug

      sysXbar.input("in0") <-- core0.mem
      sysXbar.input("in1") <-- core1.mem
      sysXbar.input("in2") <-- dma.mem
      sysXbar.input("in3") <-- debugSb
      bridge.in <-- sysXbar.output("periph")
      periphXbar.input("in") <-- bridge.out
      uart.in <-- periphXbar.output("uart")
      gpio.in <-- periphXbar.output("gpio")

      core0.debug <-- hart0
      core1.debug <-- hart1

      // The chip's pins, all terminating in the harness — the memory port among them.
      harness.serialPins <-- uart.serial
      harness.gpioPins <-- gpio.pins
      harness.jtagPins <-- jtagPins
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
      dtmClk <-- sysPll.tap("dtm")
      dmClk <-- sysPll.tap("dm")
    }
