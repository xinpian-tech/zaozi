// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
// SPDX-License-Identifier: MIT
package com.vowstar.ditdah32

import me.jiuyang.zaozi.*

case class DitDah32Parameter(
  resetVector: Int = 0,
  enableTrace: Boolean = false,
  enableDebug: Boolean = false)
    extends Parameter:
  require((resetVector & 0x3) == 0, "resetVector must be 32-bit aligned")
  require(resetVector >= 0, "resetVector must be non-negative in the initial scaffold")

  val xlen:              Int = 32
  val registerCount:     Int = 16
  val registerIndexBits: Int = 4

given upickle.default.ReadWriter[DitDah32Parameter] = upickle.default.macroRW
