package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.{Bundle, Record}


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

class JtagRecord extends Record:
  val tms   = Flipped("tms", Bool())
  val tdi   = Flipped("tdi", Bool())
  val trstN = Flipped("trstN", Bool())
  val tdo   = Aligned("tdo", Bool())

class JtagBundle extends Bundle:
  val tms   = Flipped(Bool())
  val tdi   = Flipped(Bool())
  val trstN = Flipped(Bool())
  val tdo   = Aligned(Bool())
