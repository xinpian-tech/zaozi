// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import me.jiuyang.zaozi.{DVRecord, Generator, HWRecord, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.{Data, Record}
import upickle.default.ReadWriter

/** The demo SoC's IPs as plain zaozi modules — zaozi API only, not a single syntheke construct. Each IP is a
  * serializable Parameter (once wrapped, exactly what negotiation computes as the module's FullParam, doc
  * @sec-two-layer-params),
  *   an IO record over the shared AXI bundle shapes, and a generator. The architectures are placeholder bodies
  *   (`dontCare()`): real IP logic is orthogonal to what syntheke enacts — ports, hierarchy, wiring and parameters are
  *   all real and checked (@dec-binding-check).
  *
  * The syntheke wrap — registry entries, endpoint classes, backend bindings — lives in `AxiLibrary.scala`.
  */

// The @generator macro derives a mainargs CLI for every Parameter; nested fields read as JSON tokens.
private def jsonTokens[T: ReadWriter](name: String): mainargs.TokensReader.Simple[T] =
  new mainargs.TokensReader.Simple[T]:
    def shortName = name
    def read(strs: Seq[String]): Either[String, T] =
      try Right(upickle.default.read[T](strs.last))
      catch case e: Exception => Left(e.getMessage)
given mainargs.TokensReader.Simple[AxiShape] = jsonTokens("axi-shape")
given mainargs.TokensReader.Simple[Vector[(String, AxiShape)]] = jsonTokens("axi-ports")

// ============ AXI bundle shapes mirroring Axi4.interfaceOf exactly ============

final case class AxiShape(addrBits: Int, dataBits: Int, idBits: Int) derives ReadWriter

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

// ============ Core: an AXI master with a local id space ============

case class CoreP(name: String, idBits: Int, maxFlight: Int, port: AxiShape) extends Parameter derives ReadWriter
class CorePLayers(p: CoreP)                                                 extends LayerInterface(p):
  def layers = Seq.empty
class CorePProbe(p: CoreP)                                                  extends DVRecord[CoreP, CorePLayers](p)
class CorePIO(p: CoreP)                                                     extends HWRecord(p):
  val mem = Aligned("mem", new AxiPortRecord(p.port))
@zaoziGenerator
object CoreGen                                                              extends Generator[CoreP, CorePLayers, CorePIO, CorePProbe]:
  def architecture(p: CoreP) = summon[Interface[CorePIO]].dontCare()

// ============ Xbar: the n×m crossbar ============

case class XbarP(
  name:        String,
  arbitration: String,
  inputs:      Vector[(String, AxiShape)],
  outputs:     Vector[(String, AxiShape)])
    extends Parameter derives ReadWriter
class XbarPLayers(p: XbarP) extends LayerInterface(p):
  def layers = Seq.empty
class XbarPProbe(p: XbarP)  extends DVRecord[XbarP, XbarPLayers](p)
class XbarPIO(p: XbarP)     extends HWRecord(p):
  val ins  = p.inputs.map((n, s) => Flipped(n, new AxiPortRecord(s)))
  val outs = p.outputs.map((n, s) => Aligned(n, new AxiPortRecord(s)))
@zaoziGenerator
object XbarGen              extends Generator[XbarP, XbarPLayers, XbarPIO, XbarPProbe]:
  def architecture(p: XbarP) = summon[Interface[XbarPIO]].dontCare()

// ============ L2: a pass-through adapter with its own writeback master ============

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

// ============ DRAM: the uncached memory slave ============

case class DramP(ranks: Int, port: AxiShape) extends Parameter derives ReadWriter
class DramPLayers(p: DramP)                  extends LayerInterface(p):
  def layers = Seq.empty
class DramPProbe(p: DramP)                   extends DVRecord[DramP, DramPLayers](p)
class DramPIO(p: DramP)                      extends HWRecord(p):
  val in = Flipped("in", new AxiPortRecord(p.port))
@zaoziGenerator
object DramGen                               extends Generator[DramP, DramPLayers, DramPIO, DramPProbe]:
  def architecture(p: DramP) = summon[Interface[DramPIO]].dontCare()

// ============ WidthBridge: wide to narrow ============

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

// ============ Uart: a memory-mapped peripheral ============

case class UartP(name: String, base: Long, size: Long, port: AxiShape) extends Parameter derives ReadWriter
class UartPLayers(p: UartP)                                            extends LayerInterface(p):
  def layers = Seq.empty
class UartPProbe(p: UartP)                                             extends DVRecord[UartP, UartPLayers](p)
class UartPIO(p: UartP)                                                extends HWRecord(p):
  val in = Flipped("in", new AxiPortRecord(p.port))
@zaoziGenerator
object UartGen                                                         extends Generator[UartP, UartPLayers, UartPIO, UartPProbe]:
  def architecture(p: UartP) = summon[Interface[UartPIO]].dontCare()

// ============ Gpio: a memory-mapped peripheral ============

case class GpioP(name: String, base: Long, size: Long, port: AxiShape) extends Parameter derives ReadWriter
class GpioPLayers(p: GpioP)                                            extends LayerInterface(p):
  def layers = Seq.empty
class GpioPProbe(p: GpioP)                                             extends DVRecord[GpioP, GpioPLayers](p)
class GpioPIO(p: GpioP)                                                extends HWRecord(p):
  val in = Flipped("in", new AxiPortRecord(p.port))
@zaoziGenerator
object GpioGen                                                         extends Generator[GpioP, GpioPLayers, GpioPIO, GpioPProbe]:
  def architecture(p: GpioP) = summon[Interface[GpioPIO]].dontCare()
