// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.axi

import me.jiuyang.syntheke.*
import upickle.default.ReadWriter
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
  * Mirrors rocket-chip: the xbar prefixes each input's local AXI ids with the input index (the static remap of the
  * doc's example — downstream id bits = prefix + max local bits), addresses aggregate upward, data width flows from the
  * slave side, and the l2 adapter appends its own writeback master.
  */

// ============ FullParam types (all serializable) ============

final case class CoreFull(name: String, idBits: Int, maxFlight: Int) derives ReadWriter
final case class SlaveFull(name: String, base: Long, size: Long, dataBits: Int, idBits: Int) derives ReadWriter
final case class L2Full(capacityKiB: Int, upstreamIdBits: Int, downstreamIdBits: Int) derives ReadWriter
final case class BridgeFull(wideBeatBytes: Int, narrowBeatBytes: Int, idBits: Int) derives ReadWriter
final case class DramFull(ranks: Int, addrBits: Int, dataBits: Int, idBits: Int, masters: Vector[String])
    derives ReadWriter

final case class XbarInput(in: String, ids: IdRange) derives ReadWriter
final case class XbarRoute(out: String, address: Vector[AddressRange]) derives ReadWriter
final case class XbarFull(arbitration: String, inputs: Vector[XbarInput], routes: Vector[XbarRoute]) derives ReadWriter

object AxiSocSpec extends TestSuite:

  private def entry[FP: ReadWriter](name: String) =
    new GeneratorEntry[FP](s"demo.axi.$name")

  val coreEntry   = entry[CoreFull]("Core")
  val dmaEntry    = entry[CoreFull]("Dma")
  val xbarEntry   = entry[XbarFull]("Xbar")
  val l2Entry     = entry[L2Full]("L2")
  val dramEntry   = entry[DramFull]("Dram")
  val bridgeEntry = entry[BridgeFull]("WidthBridge")
  val slaveEntry  = entry[SlaveFull]("MmioSlave")

  /** An n×m AXI crossbar: every input reaches every output. The endpoint class declares the nodes, the full dependency
    * matrix, the id-remapping dFns and aggregating uFns, and a route-table FullParam.
    */
  final class AxiXbarPorts(
    ins:         Vector[String],
    outs:        Vector[String],
    arbitration: String
  )(
    using GeneratorScope[XbarFull])
      extends Endpoints:
    val inputs       = ins.map { n =>
      given sourcecode.Name = sourcecode.Name(n)
      inward(Axi4)
    }
    val outputs      = outs.map { n =>
      given sourcecode.Name = sourcecode.Name(n)
      outward(Axi4)
    }
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
      val inEdges = inputs.map(b => view.edgeOf(b))
      val local   = Axi4Xbar.localBits(inEdges.map(_.master))
      Right(
        XbarFull(
          arbitration = arbitration,
          inputs = ins.zip(inEdges).zipWithIndex.map { case ((n, e), i) =>
            XbarInput(n, IdRange(i << local, (i << local) + e.master.endId))
          },
          routes = outs.zip(outputs).map((n, b) => XbarRoute(n, view.edgeOf(b).slave.slaves.flatMap(_.address)))
        )
      )
    }

  def axiXbar(
    ins:         Vector[String],
    outs:        Vector[String],
    arbitration: String
  )(
    using
    ws:          WrapperScope,
    name:        sourcecode.Name,
    file:        sourcecode.File,
    line:        sourcecode.Line
  ): AxiXbarPorts =
    generator(xbarEntry)(new AxiXbarPorts(ins, outs, arbitration))

  /** One AXI master core: a boundary outward node with a local id space; the master is named after the instance. */
  final class CorePorts(
    name:      String,
    idBits:    Int,
    maxFlight: Int
  )(
    using GeneratorScope[CoreFull])
      extends Endpoints:
    parametersConst(CoreFull(name, idBits, maxFlight))
    val mem =
      outward(Axi4).dFn(_ => Right(AxiMasterPort(Vector(AxiMasterParams(name, IdRange(0, 1 << idBits), maxFlight)))))

  def core(
    entry0:    GeneratorEntry[CoreFull],
    idBits:    Int,
    maxFlight: Int
  )(
    using
    ws:        WrapperScope,
    name:      sourcecode.Name,
    file:      sourcecode.File,
    line:      sourcecode.Line
  ): CorePorts =
    generator(entry0)(new CorePorts(name.value, idBits, maxFlight))

  /** A memory-mapped peripheral slave: a boundary inward node serving one address range on a 32-bit bus. */
  final class MmioSlavePorts(
    name:           String,
    base:           Long,
    size:           Long,
    idCapacityBits: Int
  )(
    using GeneratorScope[SlaveFull])
      extends Endpoints:
    parameters { view =>
      val e = view.edgeOf(in)
      Right(SlaveFull(name, base, size, e.dataBits, e.idBits))
    }
    val in = inward(Axi4).uFn(_ =>
      Right(
        AxiSlavePort(
          slaves = Vector(
            AxiSlaveParams(
              name,
              Vector(AddressRange(base, size)),
              "PUT_EFFECTS",
              false,
              TransferSizes(1, 4),
              TransferSizes(1, 4)
            )
          ),
          beatBytes = 4,
          idCapacityBits = idCapacityBits,
          minLatency = 1
        )
      )
    )

  def mmioSlave(
    base:           Long,
    size:           Long,
    idCapacityBits: Int
  )(
    using
    ws:             WrapperScope,
    name:           sourcecode.Name,
    file:           sourcecode.File,
    line:           sourcecode.Line
  ): MmioSlavePorts =
    generator(slaveEntry)(new MmioSlavePorts(name.value, base, size, idCapacityBits))

  def buildSoc(dramIdCapacity: Int = 6, gpioBase: Long = 0x10010000L): DesignSpec =
    Design {
      val core0 = core(coreEntry, idBits = 2, maxFlight = 4)
      val core1 = core(coreEntry, idBits = 3, maxFlight = 8)
      val dma   = core(dmaEntry, idBits = 1, maxFlight = 1)

      val sysXbar = axiXbar(Vector("in0", "in1", "in2"), Vector("mem", "periph"), "roundRobin")

      // The memory branch lives one level down: sysXbar -> mem/l2 crosses the `mem` boundary.
      val mem = wrapper {
        val l2            = generator(l2Entry) {
          val in     = inward(Axi4)
          val out    = outward(Axi4)
          val (d, u) = depend(in, out)
          // The L2 appends its own writeback master after the upstream id space (an adapter transforming Down).
          out.dFn { ctx =>
            val up = ctx(d)
            Right(AxiMasterPort(up.masters :+ AxiMasterParams("l2.wb", IdRange(up.endId, up.endId + 1), 2)))
          }
          in.uFn(ctx => Right(ctx(u)))
          parameters { view =>
            Right(
              L2Full(
                capacityKiB = 512,
                upstreamIdBits = view.edgeOf(in).idBits,
                downstreamIdBits = view.edgeOf(out).idBits
              )
            )
          }
          (in, out)
        }
        val (l2In, l2Out) = l2
        val dram          = generator(dramEntry) {
          val in = inward(Axi4).uFn(_ =>
            Right(
              AxiSlavePort(
                slaves = Vector(
                  AxiSlaveParams(
                    "dram",
                    Vector(AddressRange(0x80000000L, 0x80000000L)),
                    "UNCACHED",
                    true,
                    TransferSizes(1, 64),
                    TransferSizes(1, 64)
                  )
                ),
                beatBytes = 16,
                idCapacityBits = dramIdCapacity,
                minLatency = 8
              )
            )
          )
          parameters { view =>
            val e = view.edgeOf(in)
            Right(
              DramFull(
                ranks = 2,
                addrBits = e.addrBits,
                dataBits = e.dataBits,
                idBits = e.idBits,
                masters = e.master.masters.map(_.name)
              )
            )
          }
          in
        }
        // Both endpoints live under mem, so this bind may be declared here …
        dram <-- l2Out
        l2In
      }
      // … but sysXbar -> l2 crosses the mem boundary: it must be declared in a common ancestor (the root).
      mem <-- sysXbar.outputs(0)

      // Width bridge 128 -> 32: passes masters down; upstream it re-presents the peripherals on the wide bus,
      // fragmenting bursts internally, so the supported transfer ceiling grows to its own limit.
      val bridge        = generator(bridgeEntry) {
        val wideBeatBytes       = 16
        val maxUpstreamTransfer = 64
        val in                  = inward(Axi4)
        val out                 = outward(Axi4)
        val (d, u)              = depend(in, out)
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
        parameters(view => Right(BridgeFull(wideBeatBytes = 16, narrowBeatBytes = 4, idBits = view.edgeOf(in).idBits)))
        (in, out)
      }
      val (brIn, brOut) = bridge

      val periphXbar = axiXbar(Vector("in"), Vector("uart", "gpio"), "fixedPriority")

      val uart = mmioSlave(0x10000000L, 0x1000L, idCapacityBits = 8)
      val gpio = mmioSlave(gpioBase, 0x1000L, idCapacityBits = 8)

      sysXbar.inputs(0) <-- core0.mem
      sysXbar.inputs(1) <-- core1.mem
      sysXbar.inputs(2) <-- dma.mem
      brIn <-- sysXbar.outputs(1)
      periphXbar.inputs(0) <-- brOut
      uart.in <-- periphXbar.outputs(0)
      gpio.in <-- periphXbar.outputs(1)
    }

  // ============ helpers ============

  def edgeAt(resolved: ResolvedDesign, target: ModuleNodeId): AxiEdgeParams =
    resolved.edges.find(_.bind.target == target).get.edgeAs(Axi4)

  val root = ModuleId.root

  val tests = Tests {

    test("the whole SoC settles; widths, addresses and id spaces follow the graph") {
      val resolved = Negotiator.negotiate(buildSoc())
      assert(resolved.edges.sizeIs == 9)

      // DRAM edge: 3 inputs -> 2 prefix bits over max local 3 bits; l2 appends its writeback master.
      val dram = edgeAt(resolved, ModuleNodeId(root / "mem" / "dram", "in"))
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
      val core0 = edgeAt(resolved, ModuleNodeId(root / "sysXbar", "in0"))
      assert(core0.slave.slaves.map(_.name) == Vector("dram", "uart", "gpio"))
      assert(core0.dataBits == 128, core0.idBits == 2)
      // Downstream id capacity shrinks by the xbar's prefix: min(dram 6, periph 8) - 2.
      assert(core0.slave.idCapacityBits == 4)

      // The low-speed branch narrows behind the bridge: 32-bit data, 29 address bits, ids pass through.
      val uart = edgeAt(resolved, ModuleNodeId(root / "uart", "in"))
      assert(uart.dataBits == 32, uart.addrBits == 29, uart.idBits == 5)
      val wide = edgeAt(resolved, ModuleNodeId(root / "bridge", "in"))
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
      val interface        = resolved.edges.find(_.bind.target == ModuleNodeId(root / "mem" / "dram", "in")).get.interface
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
