package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.{Bundle, Record}


class ClockRecord extends Record:
  val clock = Aligned("clock", Clock())
  val reset = Aligned("reset", Reset())

class ClockBundle extends Bundle:
  val clock = Aligned(Clock())
  val reset = Aligned(Reset())

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

class IORecord extends Record:
  val inputEnable = Aligned("inputEnable", Bool())
  val outputValue = Aligned("outputValue", Bool())
  val outputEnable = Aligned("outputEnable", Bool())
  val inputValue = Flipped("inputValue", Bool())

class IOBundle extends Bundle:
  val inputEnable = Aligned(Bool())
  val outputValue = Aligned(Bool())
  val outputEnable = Aligned(Bool())
  val inputValue = Flipped(Bool())

class JtagBundle extends Bundle:
  val tms   = Flipped(Bool())
  val tdi   = Flipped(Bool())
  val trstN = Flipped(Bool())
  val tdo   = Aligned(Bool())
