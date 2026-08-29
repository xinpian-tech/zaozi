// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import com.vowstar.ditdah32.DebugRegister
import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*
import me.jiuyang.syntheke.tests.zaoziimpl.{*, given}
import me.jiuyang.zaozi.{Generator, HWBundle}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import utest.*

/** End-to-end enactment of the motivation SoC — the SoC integrator's side. The AXI IP library (zaozi generators,
  * endpoint classes, backend bindings) lives in `AxiLibrary.scala`; this file assembles the topology, negotiates over
  * [[me.jiuyang.syntheke.tests.axi.Axi4]], elaborates through the CIRCT C-API, links the per-module circuits, and
  * lowers to Verilog with the in-process firtool pipeline.
  */

/** A deliberately wrong DRAM generator: same parameter type, twice the data width — its ports disagree with the settled
  * bundle, for the binding-checkpoint test.
  */
class DramWideIO(p: DramDeviceP) extends HWBundle(p):
  val clk = Flipped(new ClockBundle)
  val in  = Flipped(new Axi4Bundle(AxiShape(p.addrBits, p.dataBits * 2, p.idBits)))
@zaoziGenerator
object DramGenWide               extends Generator[DramDeviceP, DramDevicePLayers, DramWideIO, DramDevicePProbe]:
  def architecture(p: DramDeviceP) = summon[Interface[DramWideIO]].dontCare()

object AxiVerilogSpec extends TestSuite:

  val loadBase: Long = 0x80000000L // the DRAM base: where the debugger puts the program
  // The two harts get different work, so the printed line proves both halves of the debug module's hart array: hart 0
  // wrote the program into memory for the debugger and then parks in the done-spin, hart 1 is the one that runs it.
  val hart0Pc:  Long = loadBase + 0x2c
  val hart1Pc:  Long = loadBase

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

  /** What the debugger scans in over JTAG, in place of a boot ROM: with both harts halted out of reset, write the
    * program into memory a word at a time through hart 0's abstract memory access (the address post-increments, so it
    * is set once), then select each hart in turn, give it its start PC through `dpc`, and resume it.
    */
  def injection(image: Vector[Long], base: Long, hart0Pc: Long, hart1Pc: Long): Vector[DmiWrite] =
    val accessMemoryWrite   = 0x02290000L // memory, four bytes, post-increment the address, write
    val accessRegisterWrite = 0x002307b1L // register 0x7b1 = dpc, 32 bits, transfer, write
    def dmcontrol(hart: Int, halt: Boolean, resume: Boolean): Long =
      (if halt then 0x80000000L else 0L) | (if resume then 0x40000000L else 0L) | (hart.toLong << 16) | 1L
    Vector(DmiWrite(DebugRegister.DMCONTROL, dmcontrol(0, halt = true, resume = false))) ++
      Vector(DmiWrite(DebugRegister.DATA1, base)) ++
      image.flatMap(w =>
        Vector(DmiWrite(DebugRegister.DATA0, w), DmiWrite(DebugRegister.COMMAND, accessMemoryWrite))
      ) ++
      Vector(DmiWrite(DebugRegister.DATA0, hart0Pc), DmiWrite(DebugRegister.COMMAND, accessRegisterWrite)) ++
      Vector(DmiWrite(DebugRegister.DMCONTROL, dmcontrol(0, halt = false, resume = true))) ++
      Vector(DmiWrite(DebugRegister.DMCONTROL, dmcontrol(1, halt = true, resume = false))) ++
      Vector(DmiWrite(DebugRegister.DATA0, hart1Pc), DmiWrite(DebugRegister.COMMAND, accessRegisterWrite)) ++
      Vector(DmiWrite(DebugRegister.DMCONTROL, dmcontrol(1, halt = false, resume = true)))

  /** AxiSocSpec's topology minus the L2 slot (an AXI fabric without coherence gives an L2 nothing testable to do), plus
    * the debug chain — on-chip transport and debug module, one port per hart. Everything that is not the chip is in the
    * test harness: the clock, the debug adapter driving the JTAG pins, the console on the serial pins and the board the
    * GPIO pads are wired to. FullParam = the zaozi parameter of each IP.
    */
  def buildSoc(refHz: Int = 25000000): DesignSpec =
    Design {
      // Outside the chip: the crystal, and the harness's own devices running off it.
      val harness = testHarness(
        freqHz = refHz,
        taps = Vector("ref"),
        script = injection(program, loadBase, hart0Pc, hart1Pc),
        tckDiv = 2,
        dwell = 8
      )

      // On the die: the PLL multiplies the reference to the system clock and feeds every consumer.
      val sysPll = pll(
        outHz = 100000000,
        taps = Vector(
          "core0",
          "core1",
          "dma",
          "sysXbar",
          "dram",
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

      val sysXbar = axiXbar(Vector("in0", "in1", "in2"), Vector("mem", "periph"), Arbitration.RoundRobin)

      val mem              = wrapper {
        val dram = dramCtrl(ranks = 2, wordsLog2 = 8, base = loadBase, size = 0x80000000L, idCapacityBits = 6)
        (dram.in, dram.clk)
      }
      val (memIn, dramClk) = mem
      memIn <-- sysXbar.output("mem")

      val bridge = widthBridge(wideBeatBytes = 16, maxUpstreamTransfer = 64)

      val periphXbar = axiXbar(Vector("in"), Vector("uart", "gpio"), Arbitration.FixedPriority)

      val uart = uartCtrl(0x10000000L, 0x1000L, idCapacityBits = 8, baud = 115200)
      val gpio = gpioCtrl(0x10010000L, 0x1000L, idCapacityBits = 8, width = 8)

      // The on-chip half of the debug chain: transport, module, one negotiated port per hart.
      val dtm = debugTransport(idcode = 0xdeadbeb1L, abits = 7)
      val dm  = debugModule(harts = 2, haltOnReset = true)

      sysXbar.input("in0") <-- core0.mem
      sysXbar.input("in1") <-- core1.mem
      sysXbar.input("in2") <-- dma.mem
      bridge.in <-- sysXbar.output("periph")
      periphXbar.input("in") <-- bridge.out
      uart.in <-- periphXbar.output("uart")
      gpio.in <-- periphXbar.output("gpio")

      dm.dmi <-- dtm.dmi
      core0.debug <-- dm.hart(0)
      core1.debug <-- dm.hart(1)

      // The chip's pins, all terminating in the harness.
      harness.serialPins <-- uart.serial
      harness.gpioPins <-- gpio.pins
      harness.jtagPins <-- dtm.jtag
      harness.traceClock <-- sysPll.tap("trace")

      core0.clk <-- sysPll.tap("core0")
      core1.clk <-- sysPll.tap("core1")
      dma.clk <-- sysPll.tap("dma")
      sysXbar.clk <-- sysPll.tap("sysXbar")
      dramClk <-- sysPll.tap("dram")
      bridge.clk <-- sysPll.tap("bridge")
      periphXbar.clk <-- sysPll.tap("periphXbar")
      uart.clk <-- sysPll.tap("uart")
      gpio.clk <-- sysPll.tap("gpio")
      dtm.clk <-- sysPll.tap("dtm")
      dm.clk <-- sysPll.tap("dm")
    }

  /** The SoC settles and elaborates once: every test below reads the same design, and elaborating it repeatedly would
    * hold one MLIR context per test for nothing.
    */
  lazy val soc:    ResolvedDesign   = Negotiator.negotiate(buildSoc())
  lazy val design: ElaboratedDesign = Elaborator.elaborate(soc, axiBackends)

  val tests = Tests {

    test("the AXI SoC elaborates through zaozi and the CIRCT pipeline to Verilog") {
      assert(design.circuitName == "Top")
      // The bytecode artifact is the whole linked design as the framework built it, before firtool rewrote it.
      assert(design.mlirbc.nonEmpty)
      // Verilog contains the root, the mem wrapper, and one deduplicated module per generator parameter.
      assert(design.verilog.contains("Top.sv"))
      assert(design.verilog.contains("mem.sv"))
      assert(design.moduleNames(ModuleId.root) == "Top")
      val coreModules = Set("core0", "core1").map(n => design.moduleNames(ModuleId.root / n))
      // core0 and core1 have different full parameters, so they keep distinct zaozi module names.
      assert(coreModules.sizeIs == 2)
      coreModules.foreach(n => assert(design.verilog.contains(s"$n.sv")))
      // The dangle port punched through the mem boundary survives into Verilog on the wrapper.
      assert(design.verilog("mem.sv").contains("inst_dram_node_in_in"))
      // The vendored RV32EC links in: the shim instantiates DitDah32 and the linker resolves zaozi's extmodule stub
      // to the dumped definition, transitively (the GPR too). The two cores differ only in their id space, which is
      // the shim's parameter, so the two shims hold the same deduplicated DitDah32.
      assert(design.verilog.keys.exists(_.startsWith("DitDah32_")))
      assert(design.verilog.keys.exists(_.startsWith("DitDah32Gpr_")))
      // Every fabric module is real RTL now: the crossbars, the RAM, the bridge and the peripherals hold state.
      assert(design.verilog.values.exists(_.contains("always @(posedge")))
    }

    test("the debug chain is three negotiated edges: pins, DMI bus, one interrupt port per hart") {
      val resolved = soc

      val root = ModuleId.root
      // The transport publishes the TAP it implements; the harness takes it and knows how to scan it.
      val tap  = resolved.edgeAt(ModuleNodeId(root / "harness", "jtagPins")).edgeAs(Jtag)
      assert(tap == JtagTap(0xdeadbeb1L, 5, 7, 32, 0x11))
      // The DMI bus settled at the transport's scan width, checked against the module's register file.
      assert(resolved.edgeAt(ModuleNodeId(root / "dm", "dmi")).edgeAs(Dmi) == DmiEdge(7, 32))
      // One debug port per hart, each carrying its hart id downward and the hart's register width upward.
      assert(resolved.edgeAt(ModuleNodeId(root / "core0", "debug")).edgeAs(DebugInterrupt) == DebugEdge(0, 32))
      assert(resolved.edgeAt(ModuleNodeId(root / "core1", "debug")).edgeAs(DebugInterrupt) == DebugEdge(1, 32))

      // The debug module's full parameter counts the harts the topology gave it.
      val dm = resolved.generatorModule(root / "dm").get.fullParam.asInstanceOf[DmP]
      assert(dm.harts == 2)
      assert(dm.haltOnReset)

      // Transport and module are their own modules, and the module reaches both harts.
      assert(design.verilog.keys.exists(_.startsWith("Dtm_")))
      assert(design.verilog.keys.exists(_.startsWith("Dm_")))
      assert(design.verilog.collectFirst { case (n, c) if n.startsWith("Dm_") => c }.get.contains("hart0_halt"))
      assert(design.verilog.collectFirst { case (n, c) if n.startsWith("Dm_") => c }.get.contains("hart1_halt"))
      // The debug adapter is inside the harness, not the chip: one instance, and it is the harness that holds it.
      assert(design.verilog.keys.exists(_.startsWith("JtagHost_")))
      assert(design.verilog.keys.exists(_.startsWith("TestHarness_")))
      val harnessBody = design.verilog.collectFirst { case (n, c) if n.startsWith("TestHarness_") => c }.get
      assert(harnessBody.contains("JtagHost_"))
      assert(harnessBody.contains("ClockGen"))
    }

    test("one crystal outside, one PLL on the die: every rate is derived where it is used") {
      val resolved = soc
      val root     = ModuleId.root

      // The crystal's frequency flows down to the PLL, which reports the loop it settled on.
      assert(resolved.edgeAt(ModuleNodeId(root / "sysPll", "ref")).edgeAs(ClockDomain) == 25000000)
      val sysPll = resolved.generatorModule(root / "sysPll").get.fullParam.asInstanceOf[PllP]
      assert((sysPll.mult, sysPll.div) == (4, 1)) // 25 MHz * 4 = 100 MHz
      // Every consumer on the die sees the multiplied clock.
      assert(resolved.edgeAt(ModuleNodeId(root / "uart", "clk")).edgeAs(ClockDomain) == 100000000)

      // The UART and the console sit in different clock domains on the same wire, and each computes its own
      // divisor from the frequency at its own edge.
      val uart    = resolved.generatorModule(root / "uart").get.fullParam.asInstanceOf[UartDeviceP]
      val harness = resolved.generatorModule(root / "harness").get.fullParam.asInstanceOf[TestHarnessP]
      assert(uart.divisor == 868)             // 100 MHz / 115200
      assert(harness.consoleP.divisor == 217) // 25 MHz / 115200
    }

    test("a reference the loop cannot lock onto fails the PLL's capability check") {
      // 100 MHz from a 3 MHz crystal needs a 100/3 loop, past the dividers the PLL has.
      val e = intercept[NegotiationException](Negotiator.negotiate(buildSoc(refHz = 3000000)))
      assert(e.getMessage.contains("needs a 100/3 loop"))
    }

    test("the SoC boots from JTAG and prints over the UART — verilator runs the linked Verilog") {
      // The three external modules stay external: instantiated, never defined, so the models below are what
      // gives them a body.
      Seq("ClockGen", "SimConsole", "PllAnalog", "TraceLog").foreach { m =>
        assert(design.verilog.values.exists(_.contains(s"$m ")))
        assert(!design.verilog.contains(s"$m.sv"))
      }

      // The design generates its own clock and ends its own run, so the simulation is just Top plus the
      // behavioral definitions — no ports, no testbench file.
      val simDir = os.temp.dir(prefix = "syntheke-axi-sim")
      // The design is a file set: `Top.sv` is the release netlist, and the `layers-*.sv` collateral carries the bind
      // statements that put the trace into it. A DV build compiles both — which is what the trace log needs, and what
      // a production build would leave out.
      design.verilog.foreach((name, content) => os.write(simDir / name, content))
      os.write(simDir / "ClockGen.sv", clockGenModel)
      os.write(simDir / "SimConsole.sv", simConsoleModel)
      os.write(simDir / "PllAnalog.sv", pllAnalogModel)
      os.write(simDir / "TraceLog.sv", traceLogModel)
      // firtool's own file list is the release build; the layer collateral on top of it is the verification build.
      val layers = design.verilog.keys.toSeq.sorted.filter(_.startsWith("layers-"))
      os.proc(
        "verilator",
        "--binary",
        "--timing",
        "-Wno-fatal",
        "-j",
        "0",
        s"-I$simDir",
        "--top-module",
        "Top",
        "-f",
        "filelist.f",
        layers,
        Seq("ClockGen.sv", "SimConsole.sv", "PllAnalog.sv", "TraceLog.sv")
      ).call(cwd = simDir)
      val run    = os.proc((simDir / "obj_dir" / "VTop").toString).call(cwd = simDir)
      assert(run.out.text().contains("hello world"))

      // The trace made it out of both harts, through the framework's probe routing, into the harness: hart 1 ran the
      // program from its first instruction, hart 0 sat in the done-spin.
      val core1Trace = os.read(simDir / "trace-core1.log")
      assert(core1Trace.startsWith(f"${hart1Pc}%08x: ${program.head}%08x"))
      assert(os.read(simDir / "trace-core0.log").linesIterator.forall(_.startsWith(f"${hart0Pc}%08x")))

      // A verilated build is a few hundred megabytes; keep it only when something above failed.
      os.remove.all(simDir)
    }

    // Last: the wide generator dumps its `.mlirbc` under the same canonical module name before the port check rejects
    // it, poisoning the shared dump directory for any later elaboration of the same entry and parameter.
    test("a backend interface that differs from the settled bundle is a binding-check error") {
      val resolved = Negotiator.negotiate(buildSoc())
      val mangled  = axiBackends.map {
        case b if b.entry eq Dram => ZaoziBackend(Dram, DramGenWide)
        case b                    => b
      }
      val e        = intercept[ElaborationException](Elaborator.elaborate(resolved, mangled))
      assert(e.getMessage.contains("port mismatch at mem.dram#in"))
      assert(e.getMessage.contains("in.w.bits.data")) // the first-divergence path names the widened leaf
    }
  }
