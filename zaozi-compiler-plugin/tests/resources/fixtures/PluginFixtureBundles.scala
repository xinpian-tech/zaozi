// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package fixtures

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*

class TestBundle extends Bundle:
  val input  = Aligned(UInt(8))
  val output = Flipped(UInt(8))
  val flag   = Aligned(Bool())

class OuterBundle extends Bundle:
  val inner = Aligned(new TestBundle)
  val data  = Aligned(UInt(32))
  val maybe = Option.when(true)(Aligned(UInt(4)))

case class FixtureParameter(width: Int) extends Parameter

class FixtureIO(parameter: FixtureParameter) extends HWBundle(parameter):
  val in     = Flipped(UInt(parameter.width))
  val out    = Aligned(UInt(parameter.width))
  val bundle = Aligned(new TestBundle)
  val nested = Aligned(new OuterBundle)
