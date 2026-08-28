// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

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
  val id    = Aligned(UInt(s.idBits))
  val addr  = Aligned(UInt(s.addrBits))
  val len   = Aligned(UInt(8))
  val size  = Aligned(UInt(3))
  val burst = Aligned(UInt(2))

class AxiWBundle(s: AxiShape) extends Bundle:
  val data = Aligned(UInt(s.dataBits))
  val strb = Aligned(UInt(s.dataBits / 8))
  val last = Aligned(Bool())

class AxiBBundle(s: AxiShape) extends Bundle:
  val id   = Aligned(UInt(s.idBits))
  val resp = Aligned(UInt(2))

class AxiRBundle(s: AxiShape) extends Bundle:
  val id   = Aligned(UInt(s.idBits))
  val data = Aligned(UInt(s.dataBits))
  val resp = Aligned(UInt(2))
  val last = Aligned(Bool())

class AxiChannel[B <: Bundle](bits0: B) extends Bundle:
  val valid = Aligned(Bool())
  val ready = Flipped(Bool())
  val bits  = Aligned(bits0)

class Axi4Bundle(s: AxiShape) extends Bundle:
  val aw = Aligned(new AxiChannel(new AxiAxBundle(s)))
  val w  = Aligned(new AxiChannel(new AxiWBundle(s)))
  val b  = Flipped(new AxiChannel(new AxiBBundle(s)))
  val ar = Aligned(new AxiChannel(new AxiAxBundle(s)))
  val r  = Flipped(new AxiChannel(new AxiRBundle(s)))
