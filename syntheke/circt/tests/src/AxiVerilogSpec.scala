// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*
import me.jiuyang.syntheke.tests.axi.{
  AddressRange,
  Axi4,
  Axi4Xbar,
  AxiEdgeParams,
  AxiMasterParams,
  AxiMasterPort,
  AxiSlaveParams,
  AxiSlavePort,
  IdRange,
  TransferSizes
}
import me.jiuyang.zaozi.{DVRecord, Generator, HWRecord, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.{Bundle, Data, Record}
import upickle.default.ReadWriter
import utest.*

/** End-to-end enactment of the motivation SoC: negotiate over [[Axi4]], bind every generator entry to a zaozi
  * generator, elaborate wrapper modules through the CIRCT C-API, link the per-module circuits, and lower to Verilog
  * with the in-process firtool pipeline.
  *
  * The zaozi architectures are placeholder bodies (`dontCare()`): real IP logic is orthogonal to what syntheke enacts —
  * ports, hierarchy, wiring and parameters are all real and checked (@dec-binding-check).
  */

// ============ AXI bundle shapes mirroring Axi4.interfaceOf exactly ============

final case class AxiShape(addrBits: Int, dataBits: Int, idBits: Int) derives ReadWriter
object AxiShape:
  def of(e: AxiEdgeParams): AxiShape = AxiShape(e.addrBits, e.dataBits, e.idBits)

// The @generator macro derives a mainargs CLI for every Parameter; nested fields read as JSON tokens.
private def jsonTokens[T: ReadWriter](name: String): mainargs.TokensReader.Simple[T] =
  new mainargs.TokensReader.Simple[T]:
    def shortName = name
    def read(strs: Seq[String]): Either[String, T] =
      try Right(upickle.default.read[T](strs.last))
      catch case e: Exception => Left(e.getMessage)
given mainargs.TokensReader.Simple[AxiShape] = jsonTokens("axi-shape")
given mainargs.TokensReader.Simple[Vector[(String, AxiShape)]] = jsonTokens("axi-ports")

class AxBits(s: AxiShape) extends Record:
  val id    = Aligned("id", UInt(s.idBits))
  val addr  = Aligned("addr", UInt(s.addrBits))
  val len   = Aligned("len", UInt(8))
  val size  = Aligned("size", UInt(3))
  val burst = Aligned("burst", UInt(2))

class WBits(s: AxiShape) extends Record:
  val data = Aligned("data", UInt(s.dataBits))
  val strb = Aligned("strb", UInt(s.dataBits / 8))
  val last = Aligned("last", Bool())

class BBits(s: AxiShape) extends Record:
  val id   = Aligned("id", UInt(s.idBits))
  val resp = Aligned("resp", UInt(2))

class RBits(s: AxiShape) extends Record:
  val id   = Aligned("id", UInt(s.idBits))
  val data = Aligned("data", UInt(s.dataBits))
  val resp = Aligned("resp", UInt(2))
  val last = Aligned("last", Bool())

class Channel[B <: Data](bits0: B) extends Record:
  val valid = Aligned("valid", Bool())
  val ready = Flipped("ready", Bool())
  val bits  = Aligned("bits", bits0)

class AxiPortRecord(s: AxiShape) extends Record:
  val aw = Aligned("aw", new Channel(new AxBits(s)))
  val w  = Aligned("w", new Channel(new WBits(s)))
  val b  = Flipped("b", new Channel(new BBits(s)))
  val ar = Aligned("ar", new Channel(new AxBits(s)))
  val r  = Flipped("r", new Channel(new RBits(s)))

// ============ zaozi generators, one per syntheke generator entry ============

case class CoreP(name: String, idBits: Int, maxFlight: Int, port: AxiShape) extends Parameter derives ReadWriter
class CorePLayers(p: CoreP)                                                 extends LayerInterface(p):
  def layers = Seq.empty
class CorePProbe(p: CoreP)                                                  extends DVRecord[CoreP, CorePLayers](p)
class CorePIO(p: CoreP)                                                     extends HWRecord(p):
  val mem = Aligned("mem", new AxiPortRecord(p.port))
@zaoziGenerator
object CoreGen                                                              extends Generator[CoreP, CorePLayers, CorePIO, CorePProbe]:
  def architecture(p: CoreP) = summon[Interface[CorePIO]].dontCare()

case class XbarP(
  name:        String,
  arbitration: String,
  inputs:      Vector[(String, AxiShape)],
  outputs:     Vector[(String, AxiShape)])
    extends Parameter derives ReadWriter
class XbarPLayers(p: XbarP)                                                 extends LayerInterface(p):
  def layers = Seq.empty
class XbarPProbe(p: XbarP)                                                  extends DVRecord[XbarP, XbarPLayers](p)
class XbarPIO(p: XbarP)                                                     extends HWRecord(p):
  val ins  = p.inputs.map((n, s) => Flipped(n, new AxiPortRecord(s)))
  val outs = p.outputs.map((n, s) => Aligned(n, new AxiPortRecord(s)))
@zaoziGenerator
object XbarGen                                                              extends Generator[XbarP, XbarPLayers, XbarPIO, XbarPProbe]:
  def architecture(p: XbarP) = summon[Interface[XbarPIO]].dontCare()

case class L2P(capacityKiB: Int, up: AxiShape, down: AxiShape) extends Parameter derives ReadWriter
class L2PLayers(p: L2P)                                        extends LayerInterface(p):
  def layers = Seq.empty
class L2PProbe(p: L2P)                                         extends DVRecord[L2P, L2PLayers](p)
class L2PIO(p: L2P)                                            extends HWRecord(p):
  val in  = Flipped("in", new AxiPortRecord(p.up))
  val out = Aligned("out", new AxiPortRecord(p.down))
@zaoziGenerator
object L2Gen                                                   extends Generator[L2P, L2PLayers, L2PIO, L2PProbe]:
  def architecture(p: L2P) = summon[Interface[L2PIO]].dontCare()

case class DramP(ranks: Int, port: AxiShape) extends Parameter derives ReadWriter
class DramPLayers(p: DramP)                  extends LayerInterface(p):
  def layers = Seq.empty
class DramPProbe(p: DramP)                   extends DVRecord[DramP, DramPLayers](p)
class DramPIO(p: DramP)                      extends HWRecord(p):
  val in = Flipped("in", new AxiPortRecord(p.port))
@zaoziGenerator
object DramGen                               extends Generator[DramP, DramPLayers, DramPIO, DramPProbe]:
  def architecture(p: DramP) = summon[Interface[DramPIO]].dontCare()

case class BridgeP(wide: AxiShape, narrow: AxiShape) extends Parameter derives ReadWriter
class BridgePLayers(p: BridgeP)                      extends LayerInterface(p):
  def layers = Seq.empty
class BridgePProbe(p: BridgeP)                       extends DVRecord[BridgeP, BridgePLayers](p)
class BridgePIO(p: BridgeP)                          extends HWRecord(p):
  val in  = Flipped("in", new AxiPortRecord(p.wide))
  val out = Aligned("out", new AxiPortRecord(p.narrow))
@zaoziGenerator
object BridgeGen                                     extends Generator[BridgeP, BridgePLayers, BridgePIO, BridgePProbe]:
  def architecture(p: BridgeP) = summon[Interface[BridgePIO]].dontCare()

case class SlaveP(name: String, base: Long, size: Long, port: AxiShape) extends Parameter derives ReadWriter
class SlavePLayers(p: SlaveP)                                           extends LayerInterface(p):
  def layers = Seq.empty
class SlavePProbe(p: SlaveP)                                            extends DVRecord[SlaveP, SlavePLayers](p)
class SlavePIO(p: SlaveP)                                               extends HWRecord(p):
  val in = Flipped("in", new AxiPortRecord(p.port))
@zaoziGenerator
object SlaveGen                                                         extends Generator[SlaveP, SlavePLayers, SlavePIO, SlavePProbe]:
  def architecture(p: SlaveP) = summon[Interface[SlavePIO]].dontCare()

// ============ the SoC: same topology as AxiSocSpec, FullParam = the zaozi parameter ============

object AxiVerilogSpec extends TestSuite:

  def entry[FP: ReadWriter](name: String) =
    new GeneratorEntry[FP](GeneratorId(s"demo.axi.zaozi.$name", "1"), Codec.fromReadWriter[FP](ujson.Str(name)))

  val coreEntry   = entry[CoreP]("Core")
  val xbarEntry   = entry[XbarP]("Xbar")
  val l2Entry     = entry[L2P]("L2")
  val dramEntry   = entry[DramP]("Dram")
  val bridgeEntry = entry[BridgeP]("WidthBridge")
  val slaveEntry  = entry[SlaveP]("MmioSlave")

  val backends: Seq[GeneratorBackend] = Seq(
    ZaoziBackend(coreEntry, CoreGen, identity[CoreP]),
    ZaoziBackend(xbarEntry, XbarGen, identity[XbarP]),
    ZaoziBackend(l2Entry, L2Gen, identity[L2P]),
    ZaoziBackend(dramEntry, DramGen, identity[DramP]),
    ZaoziBackend(bridgeEntry, BridgeGen, identity[BridgeP]),
    ZaoziBackend(slaveEntry, SlaveGen, identity[SlaveP])
  )

  def shapeOf(view: EdgeView, node: String): AxiShape = AxiShape.of(view(node).edge.edgeAs(Axi4))

  def axiXbarBody(
    ins:         Vector[String],
    outs:        Vector[String],
    name:        String,
    arbitration: String
  )(
    using
    gs:          GeneratorScope[XbarP],
    loc:         SourceLocation
  ): (Vector[InwardNodeBuilder[Axi4.type]], Vector[OutwardNodeBuilder[Axi4.type]]) =
    val inBs  = ins.map(inward(Axi4)(_))
    val outBs = outs.map(outward(Axi4)(_))
    val grid  = outBs.map(out => inBs.map(in => depend(in, out)))
    outBs.zipWithIndex.foreach { (out, oi) =>
      val readers = grid(oi).map(_._1)
      out.dFn(ctx => Right(Axi4Xbar.mapInputs(readers.map(ctx(_)))))
    }
    inBs.zipWithIndex.foreach { (in, ii) =>
      val readers = grid.map(_(ii)._2)
      in.uFn(ctx => Axi4Xbar.aggregate(readers.map(ctx(_)), inBs.size))
    }
    parameters { view =>
      Right(XbarP(name, arbitration, ins.map(n => n -> shapeOf(view, n)), outs.map(n => n -> shapeOf(view, n))))
    }(identity)
    (inBs, outBs)

  def core(
    name:      String,
    idBits:    Int,
    maxFlight: Int
  )(
    using ws:  WrapperScope
  ): OutwardNodeBuilder[Axi4.type] =
    generator(name, coreEntry) {
      parameters(view => Right(CoreP(name, idBits, maxFlight, shapeOf(view, "mem"))))(identity)
      outward(Axi4)("mem").dFn(_ =>
        Right(AxiMasterPort(Vector(AxiMasterParams(name, IdRange(0, 1 << idBits), maxFlight))))
      )
    }

  def mmioSlave(
    name:           String,
    base:           Long,
    size:           Long,
    idCapacityBits: Int
  )(
    using
    ws:             WrapperScope
  ): InwardNodeBuilder[Axi4.type] =
    generator(name, slaveEntry) {
      parameters(view => Right(SlaveP(name, base, size, shapeOf(view, "in"))))(identity)
      inward(Axi4)("in").uFn(_ =>
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
    }

  def buildSoc(): DesignSpec =
    Design {
      val core0Out = core("core0", idBits = 2, maxFlight = 4)
      val core1Out = core("core1", idBits = 3, maxFlight = 8)
      val dmaOut   = core("dma", idBits = 1, maxFlight = 1)

      val (sysIns, sysOuts) = generator("sysXbar", xbarEntry) {
        axiXbarBody(Vector("in0", "in1", "in2"), Vector("mem", "periph"), "sysXbar", "roundRobin")
      }

      val l2In = wrapper("mem") {
        val (l2In, l2Out) = generator("l2", l2Entry) {
          val in     = inward(Axi4)("in")
          val out    = outward(Axi4)("out")
          val (d, u) = depend(in, out)
          out.dFn { ctx =>
            val up = ctx(d)
            Right(AxiMasterPort(up.masters :+ AxiMasterParams("l2.wb", IdRange(up.endId, up.endId + 1), 2)))
          }
          in.uFn(ctx => Right(ctx(u)))
          parameters(view => Right(L2P(512, shapeOf(view, "in"), shapeOf(view, "out"))))(identity)
          (in, out)
        }
        val dramIn        = generator("dram", dramEntry) {
          parameters(view => Right(DramP(2, shapeOf(view, "in"))))(identity)
          inward(Axi4)("in").uFn(_ =>
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
                idCapacityBits = 6,
                minLatency = 8
              )
            )
          )
        }
        dramIn <-- l2Out
        l2In
      }
      l2In <-- sysOuts(0)

      val (brIn, brOut) = generator("bridge", bridgeEntry) {
        val in     = inward(Axi4)("in")
        val out    = outward(Axi4)("out")
        val (d, u) = depend(in, out)
        out.dFn(ctx => Right(ctx(d)))
        in.uFn { ctx =>
          val narrow = ctx(u)
          Right(
            narrow.copy(
              beatBytes = 16,
              slaves = narrow.slaves.map(s =>
                s.copy(
                  supportsRead = TransferSizes(s.supportsRead.min, 64),
                  supportsWrite = TransferSizes(s.supportsWrite.min, 64)
                )
              )
            )
          )
        }
        parameters(view => Right(BridgeP(shapeOf(view, "in"), shapeOf(view, "out"))))(identity)
        (in, out)
      }

      val (perIns, perOuts) = generator("periphXbar", xbarEntry) {
        axiXbarBody(Vector("in"), Vector("uart", "gpio"), "periphXbar", "fixedPriority")
      }

      val uartIn = mmioSlave("uart", 0x10000000L, 0x1000L, idCapacityBits = 8)
      val gpioIn = mmioSlave("gpio", 0x10010000L, 0x1000L, idCapacityBits = 8)

      sysIns(0) <-- core0Out
      sysIns(1) <-- core1Out
      sysIns(2) <-- dmaOut
      brIn <-- sysOuts(1)
      perIns(0) <-- brOut
      uartIn <-- perOuts(0)
      gpioIn <-- perOuts(1)
    }

  val tests = Tests {

    test("the AXI SoC elaborates through zaozi and the CIRCT pipeline to Verilog") {
      val resolved = Negotiator.negotiate(buildSoc())
      val design   = Elaborator.elaborate(resolved, backends)

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
      val mangled  = backends.map {
        case b: ZaoziBackend[?, ?, ?, ?, ?] if b.id == dramEntry.id =>
          ZaoziBackend(
            dramEntry,
            DramGen,
            (fp: DramP) => fp.copy(port = fp.port.copy(dataBits = fp.port.dataBits * 2))
          )
        case b => b
      }
      val e        = intercept[ElaborationException](Elaborator.elaborate(resolved, mangled))
      assert(e.getMessage.contains("port mismatch at mem.dram#in"))
      assert(e.getMessage.contains("in.w.bits.data")) // the first-divergence path names the widened leaf
    }
  }
