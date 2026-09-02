// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.{Bundle, Data, Record}
import upickle.default.ReadWriter

/** The AXI4 port as zaozi sees it, mirroring `Axi4.interfaceOf` exactly.
  *
  * Every shape here comes in two flavours because a module picks by how its ports are named: the Record flavour is
  * string-keyed (`io.field[Record]("aw")`), for a module whose port list its parameter decides — the crossbar has one
  * port per name it was given; the Bundle flavour reads back typed (`io.in.aw.bits.addr`), for a module with a fixed
  * port list. Both produce the same port structure, so either side of a settled edge may use either.
  */

final case class AxiShape(addrBits: Int, dataBits: Int, idBits: Int) derives ReadWriter

given axiShapeTokens: mainargs.TokensReader.Simple[AxiShape]                   = jsonTokens("axi-shape")
given axiPortsTokens: mainargs.TokensReader.Simple[Vector[(String, AxiShape)]] = jsonTokens("axi-ports")

class AxRecord(s: AxiShape) extends Record:
  val id    = Aligned("id", Bits(s.idBits))
  val addr  = Aligned("addr", Bits(s.addrBits))
  val len   = Aligned("len", Bits(8))
  val size  = Aligned("size", Bits(3))
  val burst = Aligned("burst", Bits(2))

class WRecord(s: AxiShape) extends Record:
  val data = Aligned("data", Bits(s.dataBits))
  val strb = Aligned("strb", Bits(s.dataBits / 8))
  val last = Aligned("last", Bool())

class BRecord(s: AxiShape) extends Record:
  val id   = Aligned("id", Bits(s.idBits))
  val resp = Aligned("resp", Bits(2))

class RRecord(s: AxiShape) extends Record:
  val id   = Aligned("id", Bits(s.idBits))
  val data = Aligned("data", Bits(s.dataBits))
  val resp = Aligned("resp", Bits(2))
  val last = Aligned("last", Bool())

class ChannelRecord[B <: Data](bits0: B) extends Record:
  val valid = Aligned("valid", Bool())
  val ready = Flipped("ready", Bool())
  val bits  = Aligned("bits", bits0)

class AxiPortRecord(s: AxiShape) extends Record:
  val aw = Aligned("aw", new ChannelRecord(new AxRecord(s)))
  val w  = Aligned("w", new ChannelRecord(new WRecord(s)))
  val b  = Flipped("b", new ChannelRecord(new BRecord(s)))
  val ar = Aligned("ar", new ChannelRecord(new AxRecord(s)))
  val r  = Flipped("r", new ChannelRecord(new RRecord(s)))

class AxBundle(s: AxiShape) extends Bundle:
  val id    = Aligned(Bits(s.idBits))
  val addr  = Aligned(Bits(s.addrBits))
  val len   = Aligned(Bits(8))
  val size  = Aligned(Bits(3))
  val burst = Aligned(Bits(2))

class WBundle(s: AxiShape) extends Bundle:
  val data = Aligned(Bits(s.dataBits))
  val strb = Aligned(Bits(s.dataBits / 8))
  val last = Aligned(Bool())

class BBundle(s: AxiShape) extends Bundle:
  val id   = Aligned(Bits(s.idBits))
  val resp = Aligned(Bits(2))

class RBundle(s: AxiShape) extends Bundle:
  val id   = Aligned(Bits(s.idBits))
  val data = Aligned(Bits(s.dataBits))
  val resp = Aligned(Bits(2))
  val last = Aligned(Bool())

class ChannelBundle[B <: Bundle](bits0: B) extends Bundle:
  val valid = Aligned(Bool())
  val ready = Flipped(Bool())
  val bits  = Aligned(bits0)

class AxiPortBundle(s: AxiShape) extends Bundle:
  val aw = Aligned(new ChannelBundle(new AxBundle(s)))
  val w  = Aligned(new ChannelBundle(new WBundle(s)))
  val b  = Flipped(new ChannelBundle(new BBundle(s)))
  val ar = Aligned(new ChannelBundle(new AxBundle(s)))
  val r  = Flipped(new ChannelBundle(new RBundle(s)))
