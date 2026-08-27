// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*
import me.jiuyang.zaozi.{Generator, HWRecord}
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
class DramPWideIO(p: DramP) extends HWRecord(p):
  val in = Flipped("in", new AxiPortRecord(p.port.copy(dataBits = p.port.dataBits * 2)))
@zaoziGenerator
object DramGenWide          extends Generator[DramP, DramPLayers, DramPWideIO, DramPProbe]:
  def architecture(p: DramP) = summon[Interface[DramPWideIO]].dontCare()

object AxiVerilogSpec extends TestSuite:

  /** Same topology as AxiSocSpec, with FullParam = the zaozi parameter of each IP. */
  def buildSoc(): DesignSpec =
    Design {
      val core0 = core(idBits = 2, maxFlight = 4)
      val core1 = core(idBits = 3, maxFlight = 8)
      val dma   = core(idBits = 1, maxFlight = 1)

      val sysXbar = axiXbar(Vector("in0", "in1", "in2"), Vector("mem", "periph"), "roundRobin")

      val mem = wrapper {
        val l2   = l2Cache(capacityKiB = 512)
        val dram = dramCtrl(ranks = 2, base = 0x80000000L, size = 0x80000000L, idCapacityBits = 6)
        dram.in <-- l2.out
        l2.in
      }
      mem <-- sysXbar.output("mem")

      val bridge = widthBridge(wideBeatBytes = 16, maxUpstreamTransfer = 64)

      val periphXbar = axiXbar(Vector("in"), Vector("uart", "gpio"), "fixedPriority")

      val uart = mmioSlave(0x10000000L, 0x1000L, idCapacityBits = 8)
      val gpio = mmioSlave(0x10010000L, 0x1000L, idCapacityBits = 8)

      sysXbar.input("in0") <-- core0.mem
      sysXbar.input("in1") <-- core1.mem
      sysXbar.input("in2") <-- dma.mem
      bridge.in <-- sysXbar.output("periph")
      periphXbar.input("in") <-- bridge.out
      uart.in <-- periphXbar.output("uart")
      gpio.in <-- periphXbar.output("gpio")
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
    }

    test("a backend interface that differs from the settled bundle is a binding-check error") {
      val resolved = Negotiator.negotiate(buildSoc())
      val mangled  = axiBackends.map {
        case b if b.entry eq dramEntry => ZaoziBackend(dramEntry, DramGenWide)
        case b                         => b
      }
      val e        = intercept[ElaborationException](Elaborator.elaborate(resolved, mangled))
      assert(e.getMessage.contains("port mismatch at mem.dram#in"))
      assert(e.getMessage.contains("in.w.bits.data")) // the first-divergence path names the widened leaf
    }
  }
