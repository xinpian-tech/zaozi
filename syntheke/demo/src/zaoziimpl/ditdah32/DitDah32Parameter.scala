package com.vowstar.ditdah32

import me.jiuyang.zaozi.*

case class DitDah32Parameter(
  cpuMillivolts: Int,
  retentionMillivolts: Int,
  resetVector: Int = 0,
  enableTrace: Boolean = false,
  enableDebug: Boolean = false)
    extends Parameter:
  require(cpuMillivolts > 0, s"CPU voltage $cpuMillivolts mV must be positive")
  require(retentionMillivolts > 0, s"retention voltage $retentionMillivolts mV must be positive")
  require((resetVector & 0x3) == 0, "resetVector must be 32-bit aligned")
  require(resetVector >= 0, "resetVector must be non-negative in the initial scaffold")

  val xlen:              Int = 32
  val registerCount:     Int = 16
  val registerIndexBits: Int = 4

given upickle.default.ReadWriter[DitDah32Parameter] = upickle.default.macroRW
