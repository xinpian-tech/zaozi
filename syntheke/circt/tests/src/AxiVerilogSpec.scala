// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

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

  /** The boot program, hand-assembled RV32E (registers x5–x8), loaded at 0x2000_0000: walk the string at +0x40 and
    * write each byte to the UART's TXDATA, polling STATUS bit0 (txBusy) between bytes; a NUL ends the walk at the spin
    * at +0x2c — which is also where core1 parks, fetching its one-instruction loop forever.
    *
    * {{{
    * 00: 100002B7  lui  x5, 0x10000    ; x5 = UART base
    * 04: 20000337  lui  x6, 0x20000    ; x6 = ROM base
    * 08: 04030313  addi x6, x6, 0x40   ; x6 = &"hello world\n"
    * 0c: 00030383  lb   x7, 0(x6)      ; next char
    * 10: 00038E63  beq  x7, x0, +0x1c  ; NUL -> 2c
    * 14: 0082A403  lw   x8, 8(x5)      ; STATUS
    * 18: 00147413  andi x8, x8, 1      ; txBusy
    * 1c: FE041CE3  bne  x8, x0, -8     ; busy -> 14
    * 20: 0072A023  sw   x7, 0(x5)      ; TXDATA = char
    * 24: 00130313  addi x6, x6, 1
    * 28: FE5FF06F  jal  x0, -0x1c      ; -> 0c
    * 2c: 0000006F  jal  x0, 0          ; done: spin (core1's reset pc)
    * 40: "hello world\n\0"
    * }}}
    */
  val bootImage: Vector[Long] = Vector(
    0x100002b7L, 0x20000337L, 0x04030313L, 0x00030383L, 0x00038e63L, 0x0082a403L, 0x00147413L, 0xfe041ce3L, 0x0072a023L,
    0x00130313L, 0xfe5ff06fL, 0x0000006fL, 0L, 0L, 0L, 0L, 0x6c6c6568L, 0x6f77206fL, 0x0a646c72L, 0L
  )

  /** AxiSocSpec's topology minus the L2 slot (an AXI fabric without coherence gives an L2 nothing testable to do), plus
    * the clock tree, the boot ROM the cores reset into, and the console testbench terminating the serial pins;
    * FullParam = the zaozi parameter of each IP.
    */
  def buildSoc(): DesignSpec =
    Design {
      val clkSrc = clockSource(
        100000000,
        Vector("core0", "core1", "dma", "sysXbar", "rom", "dram", "bridge", "periphXbar", "uart", "gpio", "tb")
      )

      val core0 = core(idBits = 2, maxFlight = 4, resetPc = 0x20000000)
      val core1 = core(idBits = 3, maxFlight = 8, resetPc = 0x2000002c)
      val dma   = dmaCtrl(idBits = 1, maxFlight = 1, targetBase = 0x80000000L, windowLog2 = 10)

      val sysXbar = axiXbar(Vector("in0", "in1", "in2"), Vector("mem", "periph", "boot"), Arbitration.RoundRobin)

      val rom = bootRom(0x20000000L, 0x1000L, idCapacityBits = 6, image = bootImage)

      val mem              = wrapper {
        val dram = dramCtrl(
          ranks = 2,
          wordsLog2 = 6,
          base = 0x80000000L,
          size = 0x80000000L,
          bootAliasSize = 0x10000000L,
          idCapacityBits = 6
        )
        (dram.in, dram.clk)
      }
      val (memIn, dramClk) = mem
      memIn <-- sysXbar.output("mem")

      val bridge = widthBridge(wideBeatBytes = 16, maxUpstreamTransfer = 64)

      val periphXbar = axiXbar(Vector("in"), Vector("uart", "gpio"), Arbitration.FixedPriority)

      val uart   = uartCtrl(0x10000000L, 0x1000L, idCapacityBits = 8, baud = 115200)
      val gpio   = gpioCtrl(0x10010000L, 0x1000L, idCapacityBits = 8, width = 8)
      val tb     = console()
      val gpioPd = gpioPads()

      sysXbar.input("in0") <-- core0.mem
      sysXbar.input("in1") <-- core1.mem
      sysXbar.input("in2") <-- dma.mem
      rom.in <-- sysXbar.output("boot")
      bridge.in <-- sysXbar.output("periph")
      periphXbar.input("in") <-- bridge.out
      uart.in <-- periphXbar.output("uart")
      gpio.in <-- periphXbar.output("gpio")
      tb.serial <-- uart.serial
      gpioPd.in <-- gpio.pins

      core0.clk <-- clkSrc.tap("core0")
      core1.clk <-- clkSrc.tap("core1")
      dma.clk <-- clkSrc.tap("dma")
      sysXbar.clk <-- clkSrc.tap("sysXbar")
      rom.clk <-- clkSrc.tap("rom")
      dramClk <-- clkSrc.tap("dram")
      bridge.clk <-- clkSrc.tap("bridge")
      periphXbar.clk <-- clkSrc.tap("periphXbar")
      uart.clk <-- clkSrc.tap("uart")
      gpio.clk <-- clkSrc.tap("gpio")
      tb.clk <-- clkSrc.tap("tb")
    }

  val tests = Tests {

    test("the AXI SoC elaborates through zaozi and the CIRCT pipeline to Verilog") {
      val resolved = Negotiator.negotiate(buildSoc())
      val design   = Elaborator.elaborate(resolved, axiBackends)

      assert(design.circuitName == "Top")
      // The FIRRTL artifact holds the whole linked design: root, wrappers, and zaozi-generated modules.
      assert(design.firrtl.contains("circuit Top"))
      assert(design.firrtl.contains("module Top"))
      assert(design.firrtl.contains("module mem"))
      // Verilog contains the root, the mem wrapper, and one deduplicated module per generator parameter.
      assert(design.verilog.contains("module Top"))
      assert(design.verilog.contains("module mem"))
      assert(design.moduleNames(ModuleId.root) == "Top")
      val coreModules = Set("core0", "core1").map(n => design.moduleNames(ModuleId.root / n))
      // core0 and core1 have different full parameters, so they keep distinct zaozi module names.
      assert(coreModules.sizeIs == 2)
      coreModules.foreach(n => assert(design.verilog.contains(s"module $n")))
      // The dangle port punched through the mem boundary survives into Verilog on the wrapper.
      assert(design.firrtl.contains("inst_dram_node_in_in"))
      // The vendored RV32EC links in: the shim instantiates DitDah32 and the linker resolves zaozi's extmodule stub
      // to the dumped definition, transitively (the GPR too). The name carries the parameter hash — the two cores
      // reset at different vectors, so two DitDah32s link.
      assert(design.firrtl.contains("inst core of DitDah32_"))
      assert(design.verilog.contains("module DitDah32_"))
      assert(design.verilog.contains("module DitDah32Gpr_"))
      // Every fabric module is real RTL now: the crossbars, the RAM, the bridge and the peripherals hold state.
      assert(design.verilog.contains("always @(posedge"))
    }

    test("the SoC boots from the ROM and prints over the UART — verilator runs the linked Verilog") {
      val resolved = Negotiator.negotiate(buildSoc())
      val design   = Elaborator.elaborate(resolved, axiBackends)
      // The two declared simulation boundaries survive linking as external modules.
      assert(design.firrtl.contains("extmodule ClockGen"))
      assert(design.firrtl.contains("extmodule SimConsole"))

      // The design generates its own clock and ends its own run, so the simulation is just Top plus the two
      // behavioral definitions — no ports, no testbench file.
      val simDir = os.temp.dir(prefix = "syntheke-axi-sim")
      os.write(simDir / "Top.sv", design.verilog)
      os.write(simDir / "ClockGen.sv", clockGenModel)
      os.write(simDir / "SimConsole.sv", simConsoleModel)
      os.proc(
        "verilator",
        "--binary",
        "--timing",
        "-Wno-fatal",
        "-j",
        "0",
        "--top-module",
        "Top",
        "Top.sv",
        "ClockGen.sv",
        "SimConsole.sv"
      ).call(cwd = simDir)
      val run    = os.proc((simDir / "obj_dir" / "VTop").toString).call(cwd = simDir)
      assert(run.out.text().contains("hello world"))
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
