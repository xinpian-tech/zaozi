// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.{Data, Record}
import upickle.default.ReadWriter

/** The AXI bundle shapes shared by every zaozi IP of the demo SoC, mirroring `Axi4.interfaceOf` exactly. Each IP lives
  * in its own file in this package; the syntheke wrap — registry entries, endpoint classes, backend bindings — lives in
  * `library/` beside it.
  */

// The @generator macro derives a mainargs CLI for every Parameter; nested fields read as JSON tokens.
private[zaoziimpl] def jsonTokens[T: ReadWriter](name: String): mainargs.TokensReader.Simple[T]                          =
  new mainargs.TokensReader.Simple[T]:
    def shortName = name
    def read(strs: Seq[String]): Either[String, T] =
      try Right(upickle.default.read[T](strs.last))
      catch case e: Exception => Left(e.getMessage)
given axiShapeTokens:                                           mainargs.TokensReader.Simple[AxiShape]                   = jsonTokens("axi-shape")
given axiPortsTokens:                                           mainargs.TokensReader.Simple[Vector[(String, AxiShape)]] = jsonTokens("axi-ports")

final case class AxiShape(addrBits: Int, dataBits: Int, idBits: Int) derives ReadWriter

class AxBits(s: AxiShape) extends Record:
  val id    = Aligned("id", Bits(s.idBits))
  val addr  = Aligned("addr", Bits(s.addrBits))
  val len   = Aligned("len", Bits(8))
  val size  = Aligned("size", Bits(3))
  val burst = Aligned("burst", Bits(2))

class WBits(s: AxiShape) extends Record:
  val data = Aligned("data", Bits(s.dataBits))
  val strb = Aligned("strb", Bits(s.dataBits / 8))
  val last = Aligned("last", Bool())

class BBits(s: AxiShape) extends Record:
  val id   = Aligned("id", Bits(s.idBits))
  val resp = Aligned("resp", Bits(2))

class RBits(s: AxiShape) extends Record:
  val id   = Aligned("id", Bits(s.idBits))
  val data = Aligned("data", Bits(s.dataBits))
  val resp = Aligned("resp", Bits(2))
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

class ClockRecord extends Record:
  val clock = Aligned("clock", Clock())
  val reset = Aligned("reset", Reset())

class SerialRecord extends Record:
  val tx = Aligned("tx", Bool())
  val rx = Flipped("rx", Bool())
