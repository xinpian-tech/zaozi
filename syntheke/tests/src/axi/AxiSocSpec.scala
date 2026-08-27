// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.axi

import me.jiuyang.syntheke.*
import utest.*

/** The design document's motivation SoC (@sec-three-params), negotiated over the [[Axi4]] protocol:
  *
  * {{{
  * core0 (2 id bits) ─┐                 ┌─ mem/l2 ── mem/dram   (128-bit, id capacity 6)
  * core1 (3 id bits) ─┼─ sysXbar (3×2) ─┤
  * dma   (1 id bit)  ─┘                 └─ bridge (128→32) ── periphXbar (1×2) ─┬─ uart
  *                                                                              └─ gpio
  * }}}
  *
  * This is the SoC integrator's side: instantiate the IPs of `AxiLibrary.scala` and wire them. Mirrors rocket-chip: the
  * xbar prefixes each input's local AXI ids with the input index (the static remap of the doc's example — downstream id
  * bits = prefix + max local bits), addresses aggregate upward, data width flows from the slave side, and the l2
  * adapter appends its own writeback master.
  */
object AxiSocSpec extends TestSuite:

  def buildSoc(dramIdCapacity: Int = 6, gpioBase: Long = 0x10010000L): DesignSpec =
    Design {
      val core0 = core(idBits = 2, maxFlight = 4)
      val core1 = core(idBits = 3, maxFlight = 8)
      val dma   = dmaCtrl(idBits = 1, maxFlight = 1)

      val sysXbar = axiXbar(Vector("in0", "in1", "in2"), Vector("mem", "periph"), "roundRobin")

      // The memory branch lives one level down: sysXbar -> mem/l2 crosses the `mem` boundary.
      val mem = wrapper {
        val l2   = l2Cache(capacityKiB = 512)
        val dram = dramCtrl(ranks = 2, base = 0x80000000L, size = 0x80000000L, idCapacityBits = dramIdCapacity)
        // Both endpoints live under mem, so this bind may be declared here …
        dram.in <-- l2.out
        l2.in
      }
      // … but sysXbar -> l2 crosses the mem boundary: it must be declared in a common ancestor (the root).
      mem <-- sysXbar.output("mem")

      val bridge = widthBridge(wideBeatBytes = 16, maxUpstreamTransfer = 64)

      val periphXbar = axiXbar(Vector("in"), Vector("uart", "gpio"), "fixedPriority")

      val uart = uartCtrl(0x10000000L, 0x1000L, idCapacityBits = 8)
      val gpio = gpioCtrl(gpioBase, 0x1000L, idCapacityBits = 8)

      sysXbar.input("in0") <-- core0.mem
      sysXbar.input("in1") <-- core1.mem
      sysXbar.input("in2") <-- dma.mem
      bridge.in <-- sysXbar.output("periph")
      periphXbar.input("in") <-- bridge.out
      uart.in <-- periphXbar.output("uart")
      gpio.in <-- periphXbar.output("gpio")
    }

  val root = ModuleId.root

  val tests = Tests {

    test("the whole SoC settles; widths, addresses and id spaces follow the graph") {
      val resolved = Negotiator.negotiate(buildSoc())
      assert(resolved.edges.sizeIs == 9)

      // DRAM edge: 3 inputs -> 2 prefix bits over max local 3 bits; l2 appends its writeback master.
      val dram = resolved.edgeAt(ModuleNodeId(root / "mem" / "dram", "in")).edgeAs(Axi4)
      assert(dram.idBits == 5, dram.dataBits == 128, dram.addrBits == 32)
      assert(
        dram.master.masters.map(m => m.name -> m.id) == Vector(
          "core0" -> IdRange(0, 4),
          "core1" -> IdRange(8, 16),
          "dma"   -> IdRange(16, 18),
          "l2.wb" -> IdRange(18, 19)
        )
      )

      // Address visibility aggregates upward: core0 sees dram + uart + gpio through the xbar.
      val core0 = resolved.edgeAt(ModuleNodeId(root / "sysXbar", "in0")).edgeAs(Axi4)
      assert(core0.slave.slaves.map(_.name) == Vector("dram", "uart", "gpio"))
      assert(core0.dataBits == 128, core0.idBits == 2)
      // Downstream id capacity shrinks by the xbar's prefix: min(dram 6, periph 8) - 2.
      assert(core0.slave.idCapacityBits == 4)

      // The low-speed branch narrows behind the bridge: 32-bit data, 29 address bits, ids pass through.
      val uart = resolved.edgeAt(ModuleNodeId(root / "uart", "in")).edgeAs(Axi4)
      assert(uart.dataBits == 32, uart.addrBits == 29, uart.idBits == 5)
      val wide = resolved.edgeAt(ModuleNodeId(root / "bridge", "in")).edgeAs(Axi4)
      assert(wide.dataBits == 128, wide.slave.slaves.map(_.name) == Vector("uart", "gpio"))
    }

    test("the xbar's FullParam is a serializable route table and id map") {
      val resolved = Negotiator.negotiate(buildSoc())
      val xbar     = resolved.generatorModule(root / "sysXbar").get
      val decoded  = upickle.default.read[XbarFull](xbar.encodedFullParam)
      assert(decoded.arbitration == "roundRobin")
      assert(
        decoded.inputs == Vector(
          XbarInput("in0", IdRange(0, 4)),
          XbarInput("in1", IdRange(8, 16)),
          XbarInput("in2", IdRange(16, 18))
        )
      )
      assert(decoded.routes.map(_.out) == Vector("mem", "periph"))
      assert(decoded.routes(0).address == Vector(AddressRange(0x80000000L, 0x80000000L)))
      assert(
        decoded.routes(1).address == Vector(AddressRange(0x10000000L, 0x1000L), AddressRange(0x10010000L, 0x1000L))
      )
    }

    test("the settled interface is the five AXI4 channels with correct widths and flips") {
      val resolved         = Negotiator.negotiate(buildSoc())
      val interface        = resolved.edgeAt(ModuleNodeId(root / "mem" / "dram", "in")).interface
      assert(interface.fields.map(_.name) == Vector("aw", "w", "b", "ar", "r"))
      assert(
        interface.fields.map(_.tpe.isInstanceOf[ProtocolInterface.Flipped]) == Vector(false, false, true, false, true)
      )
      def bits(ch: String) =
        interface.fields
          .find(_.name == ch)
          .get
          .tpe
          .asInstanceOf[ProtocolInterface.Bundle]
          .fields
          .find(_.name == "bits")
          .get
          .tpe
          .asInstanceOf[ProtocolInterface.Bundle]
      assert(bits("aw").fields.find(_.name == "id").get.tpe == ProtocolInterface.UInt(5))
      assert(bits("aw").fields.find(_.name == "addr").get.tpe == ProtocolInterface.UInt(32))
      assert(bits("w").fields.find(_.name == "strb").get.tpe == ProtocolInterface.UInt(16))
      // Every channel is valid / ready(flip) / bits.
      val aw               = interface.fields.head.tpe.asInstanceOf[ProtocolInterface.Bundle]
      assert(
        aw.fields.map(f => f.name -> f.tpe.isInstanceOf[ProtocolInterface.Flipped]) ==
          Vector("valid" -> false, "ready" -> true, "bits" -> false)
      )
    }

    test("the sysXbar -> mem/l2 edge plans a dangle port through the mem boundary") {
      val resolved = Negotiator.negotiate(buildSoc())
      val mem      = root / "mem"
      val dangles  = resolved.portPlans.filter(_.module == mem)
      assert(dangles.map(p => p.name.encoded -> p.direction) == Vector("inst_l2_node_in_in" -> PortDirection.Input))
      // The l2 -> dram edge stays inside mem: one direct wire, no dangle.
      val inner    =
        resolved.wirePlans.filter(w => w.module == mem && w.from == LocalEndpoint.ChildPort("l2", PortName("out")))
      assert(inner.map(_.to) == Vector(LocalEndpoint.ChildPort("dram", PortName("in"))))
    }

    test("settled parameters reach the edges export serialized") {
      val resolved    = Negotiator.negotiate(buildSoc())
      val designEdges = Export.edges(resolved)("designEdges").arr
      assert(designEdges.exists(e => e("edge")("dataBits") == ujson.Num(128)))
      assert(
        designEdges.exists(e =>
          e("edge")("master")("masters").arr.map(_("name").str) == Seq("core0", "core1", "dma", "l2.wb")
        )
      )
    }

    test("a narrow dram id capacity fails fast at the first overflowing edge") {
      // Capacity 4: settlement runs in bind declaration order, and the first bind is l2 -> dram, whose remapped
      // id space (l2.wb appended) needs 5 bits > 4.
      val e = intercept[NegotiationException](Negotiator.negotiate(buildSoc(dramIdCapacity = 4)))
      assert(e.getMessage.contains("settle failed"))
      assert(e.getMessage.contains("mem.dram#in"))
    }

    test("overlapping peripheral addresses are caught during upward aggregation") {
      val e = intercept[NegotiationException](Negotiator.negotiate(buildSoc(gpioBase = 0x10000800L)))
      assert(e.getMessage.contains("propagation failed"))
      assert(e.getMessage.contains("periphXbar#in"))
      assert(e.getMessage.contains("overlap"))
    }
  }
