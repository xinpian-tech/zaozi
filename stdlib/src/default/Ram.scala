// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class RamParameter(width: Int, depth: Int, asyncReset: Boolean, resetMem: Boolean) extends Parameter:
  require(width >= 1 && width <= 2048, s"Ram width must be 1..2048, got $width")
  require(depth >= 2 && depth <= 1024, s"Ram depth must be 2..1024, got $depth")

  def addressWidth: Int = math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(depth - 1))

given upickle.default.ReadWriter[RamParameter] = upickle.default.macroRW

class RamLayers(parameter: RamParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class RamIO(parameter: RamParameter) extends HWBundle(parameter):
  val clock        = Flipped(Clock())
  val resetN       = Flipped(Reset())
  val chipSelectN  = Flipped(Bool())
  val writeN       = Flipped(Bool())
  val readAddress  = Flipped(UInt(parameter.addressWidth))
  val writeAddress = Flipped(UInt(parameter.addressWidth))
  val readData     = Aligned(UInt(parameter.width))
  val writeData    = Flipped(UInt(parameter.width))

class RamProbe(parameter: RamParameter) extends DVBundle[RamParameter, RamLayers](parameter)

@generator
object Ram extends Generator[RamParameter, RamLayers, RamIO, RamProbe]:
  override def moduleName(p: RamParameter): String =
    s"Ram_dataWidth${p.width}_depth${p.depth}" +
      s"_asyncReset${p.asyncReset}_resetMem${p.resetMem}"

  def architecture(parameter: RamParameter) =
    val io           = summon[Interface[RamIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope =
      if parameter.asyncReset then ResetScope.asyncActiveLow(io.resetN)
      else ResetScope.syncActiveLow(io.resetN)

    val storage = Seq.fill(parameter.depth):
      if parameter.resetMem then RegInit(0.U(parameter.width))
      else Reg(UInt(parameter.width))

    val writeEnable = !io.writeN & !io.chipSelectN

    storage.zipWithIndex.foreach: (entry, index) =>
      when(writeEnable & (io.writeAddress === index.U(parameter.addressWidth))) {
        entry := io.writeData
      }

    val readData = Wire(UInt(parameter.width))
    readData := 0.U(parameter.width)
    storage.zipWithIndex.foreach: (entry, index) =>
      when(io.readAddress === index.U(parameter.addressWidth)) {
        readData := entry
      }
    io.readData := readData
