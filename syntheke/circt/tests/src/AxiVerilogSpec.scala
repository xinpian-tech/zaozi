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

  /** Same topology as AxiSocSpec plus the clock tree and serial pins, with FullParam = the zaozi parameter of each IP.
    */
  def buildSoc(): DesignSpec =
    Design {
      val clkSrc = clockSource(
        100000000,
        Vector("core0", "core1", "dma", "sysXbar", "l2", "dram", "bridge", "periphXbar", "uart", "gpio")
      )

      val core0 = core(idBits = 2, maxFlight = 4, resetPc = 0)
      val core1 = core(idBits = 3, maxFlight = 8, resetPc = 0)
      val dma   = dmaCtrl(idBits = 1, maxFlight = 1, targetBase = 0x80000000L, windowLog2 = 10)

      val sysXbar = axiXbar(Vector("in0", "in1", "in2"), Vector("mem", "periph"), Arbitration.RoundRobin)

      val mem                     = wrapper {
        val l2   = l2Cache(capacityKiB = 512)
        val dram = dramCtrl(
          ranks = 2,
          wordsLog2 = 6,
          base = 0x80000000L,
          size = 0x80000000L,
          bootAliasSize = 0x10000000L,
          idCapacityBits = 6
        )
        dram.in <-- l2.out
        (l2.in, l2.clk, dram.clk)
      }
      val (memIn, l2Clk, dramClk) = mem
      memIn <-- sysXbar.output("mem")

      val bridge = widthBridge(wideBeatBytes = 16, maxUpstreamTransfer = 64)

      val periphXbar = axiXbar(Vector("in"), Vector("uart", "gpio"), Arbitration.FixedPriority)

      val uart   = uartCtrl(0x10000000L, 0x1000L, idCapacityBits = 8, baud = 115200)
      val gpio   = gpioCtrl(0x10010000L, 0x1000L, idCapacityBits = 8, width = 8)
      val pads   = serialPads()
      val gpioPd = gpioPads()

      sysXbar.input("in0") <-- core0.mem
      sysXbar.input("in1") <-- core1.mem
      sysXbar.input("in2") <-- dma.mem
      bridge.in <-- sysXbar.output("periph")
      periphXbar.input("in") <-- bridge.out
      uart.in <-- periphXbar.output("uart")
      gpio.in <-- periphXbar.output("gpio")
      pads.in <-- uart.serial
      gpioPd.in <-- gpio.pins

      core0.clk <-- clkSrc.tap("core0")
      core1.clk <-- clkSrc.tap("core1")
      dma.clk <-- clkSrc.tap("dma")
      sysXbar.clk <-- clkSrc.tap("sysXbar")
      l2Clk <-- clkSrc.tap("l2")
      dramClk <-- clkSrc.tap("dram")
      bridge.clk <-- clkSrc.tap("bridge")
      periphXbar.clk <-- clkSrc.tap("periphXbar")
      uart.clk <-- clkSrc.tap("uart")
      gpio.clk <-- clkSrc.tap("gpio")
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
      assert(design.firrtl.contains("inst_l2_node_in_in"))
      // The vendored RV32EC links in: the shim instantiates DitDah32 and the linker resolves zaozi's extmodule stub
      // to the dumped definition, transitively (the GPR too).
      assert(design.firrtl.contains("inst core of DitDah32"))
      assert(design.verilog.contains("module DitDah32("))
      assert(design.verilog.contains("module DitDah32Gpr("))
      // Every fabric module is real RTL now: the crossbars, the RAM, the bridge and the peripherals hold state.
      assert(design.verilog.contains("always @(posedge"))
    }

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
