// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
// SPDX-License-Identifier: MIT
package com.vowstar.ditdah32

import me.jiuyang.zaozi.*

case class DitDah32Parameter(
  resetVector: Int = 0,
  enableTrace: Boolean = false,
  enableJtag: Boolean = false,
  jtagIdcode: Long = 1L
) extends Parameter:
  require((resetVector & 0x3) == 0, "resetVector must be 32-bit aligned")
  require(resetVector >= 0, "resetVector must be non-negative in the initial scaffold")
  require(jtagIdcode >= 0L && jtagIdcode <= 0xffffffffL, "jtagIdcode must fit in 32 bits")
  require(!enableJtag || (jtagIdcode & 1L) == 1L, "jtagIdcode bit 0 must be one")

  val xlen: Int = 32
  val registerCount: Int = 16
  val registerIndexBits: Int = 4

given upickle.default.ReadWriter[DitDah32Parameter] = upickle.default.macroRW
