// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

/** Register-backed single-write-port RAM configuration. */
case class RamParameter(
  /** Number of bits stored in each RAM entry. */
  width:      Int,
  /** Number of addressable RAM entries. */
  depth:      Int,
  /** Use asynchronous active-low reset when true, or synchronous active-low reset when false. */
  asyncReset: Boolean,
  /** Reset all RAM entries to zero when true; otherwise storage has no reset behavior. */
  resetMem:   Boolean)
    extends Parameter:
  require(width >= 1 && width <= 2048, s"Ram width must be 1..2048, got $width")
  require(depth >= 2 && depth <= 1024, s"Ram depth must be 2..1024, got $depth")

  /** Number of bits used by the read and write addresses. */
  def addressWidth: Int = math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(depth - 1))

given upickle.default.ReadWriter[RamParameter] = upickle.default.macroRW

class RamLayers(parameter: RamParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class RamIO(parameter: RamParameter) extends HWBundle(parameter):
  /** Clock used for writes and optional storage reset. */
  val clock = Flipped(Clock())

  /** Active-low reset used only when `resetMem` is enabled. */
  val resetN = Flipped(Reset())

  /** Active-low qualifier for writes. */
  val chipSelectN = Flipped(Bool())

  /** Active-low write enable. */
  val writeN = Flipped(Bool())

  /** Address selected by the combinational read port. */
  val readAddress = Flipped(UInt(parameter.addressWidth))

  /** Address written on the active clock edge. */
  val writeAddress = Flipped(UInt(parameter.addressWidth))

  /** Combinational data from `readAddress`. */
  val readData = Aligned(UInt(parameter.width))

  /** Data written when both `chipSelectN` and `writeN` are low. */
  val writeData = Flipped(UInt(parameter.width))

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
