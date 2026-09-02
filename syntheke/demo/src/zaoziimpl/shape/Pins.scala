// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.{Bundle, Record}

/** What the chip's pins and its clock look like to zaozi: the clock and reset a module runs on, the serial pair, the
  * GPIO bank and the TAP. Two flavours each, for the same reason as the AXI port — see `Axi.scala`.
  */

class ClockRecord extends Record:
  val clock = Aligned("clock", Clock())
  val reset = Aligned("reset", Reset())

class ClockBundle extends Bundle:
  val clock = Aligned(Clock())
  val reset = Aligned(Reset())

class SerialRecord extends Record:
  val tx = Aligned("tx", Bool())
  val rx = Flipped("rx", Bool())

class SerialBundle extends Bundle:
  val tx = Aligned(Bool())
  val rx = Flipped(Bool())

class GpioPinsRecord(width: Int) extends Record:
  val out = Aligned("out", Bits(width))
  val oe  = Aligned("oe", Bits(width))
  val in  = Flipped("in", Bits(width))

class GpioPinsBundle(width: Int) extends Bundle:
  val out = Aligned(Bits(width))
  val oe  = Aligned(Bits(width))
  val in  = Flipped(Bits(width))

/** One JTAG TAP, from the TAP's side: driven and clocked from outside, answering on `tdo`. */
class JtagRecord extends Record:
  val tck   = Flipped("tck", Clock())
  val tms   = Flipped("tms", Bool())
  val tdi   = Flipped("tdi", Bool())
  val trstN = Flipped("trstN", Bool())
  val tdo   = Aligned("tdo", Bool())

class JtagBundle extends Bundle:
  val tck   = Flipped(Clock())
  val tms   = Flipped(Bool())
  val tdi   = Flipped(Bool())
  val trstN = Flipped(Bool())
  val tdo   = Aligned(Bool())
