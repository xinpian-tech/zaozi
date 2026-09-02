// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.Bundle

/** Bundle-flavoured shapes for the real device implementations: field names come from the vals and fields read back
  * typed (`io.in.aw.bits.addr`), unlike the Record shapes in `AxiShapes.scala` whose fields are string-keyed. Both
  * flavours produce the same port structure, so either side of a settled edge may use either.
  */

class ClockBundle extends Bundle:
  val clock = Aligned(Clock())
  val reset = Aligned(Reset())

class SerialBundle extends Bundle:
  val tx = Aligned(Bool())
  val rx = Flipped(Bool())

class AxiAxBundle(s: AxiShape) extends Bundle:
  val id    = Aligned(Bits(s.idBits))
  val addr  = Aligned(Bits(s.addrBits))
  val len   = Aligned(Bits(8))
  val size  = Aligned(Bits(3))
  val burst = Aligned(Bits(2))

class AxiWBundle(s: AxiShape) extends Bundle:
  val data = Aligned(Bits(s.dataBits))
  val strb = Aligned(Bits(s.dataBits / 8))
  val last = Aligned(Bool())

class AxiBBundle(s: AxiShape) extends Bundle:
  val id   = Aligned(Bits(s.idBits))
  val resp = Aligned(Bits(2))

class AxiRBundle(s: AxiShape) extends Bundle:
  val id   = Aligned(Bits(s.idBits))
  val data = Aligned(Bits(s.dataBits))
  val resp = Aligned(Bits(2))
  val last = Aligned(Bool())

class ChannelBundle[B <: Bundle](bits0: B) extends Bundle:
  val valid = Aligned(Bool())
  val ready = Flipped(Bool())
  val bits  = Aligned(bits0)

class Axi4Bundle(s: AxiShape) extends Bundle:
  val aw = Aligned(new ChannelBundle(new AxiAxBundle(s)))
  val w  = Aligned(new ChannelBundle(new AxiWBundle(s)))
  val b  = Flipped(new ChannelBundle(new AxiBBundle(s)))
  val ar = Aligned(new ChannelBundle(new AxiAxBundle(s)))
  val r  = Flipped(new ChannelBundle(new AxiRBundle(s)))
