// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
// SPDX-License-Identifier: MIT
package com.vowstar.ditdah32

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

class DitDah32GprLayers(parameter: DitDah32Parameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class DitDah32GprProbe(parameter: DitDah32Parameter) extends DVBundle[DitDah32Parameter, DitDah32GprLayers](parameter)

class DitDah32GprIO(parameter: DitDah32Parameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())

  val raddr1 = Flipped(UInt(5))
  val rdata1 = Aligned(UInt(parameter.xlen))
  val raddr2 = Flipped(UInt(5))
  val rdata2 = Aligned(UInt(parameter.xlen))
  val raddr3 = Flipped(UInt(5))
  val rdata3 = Aligned(UInt(parameter.xlen))

  val we       = Flipped(Bool())
  val waddr    = Flipped(UInt(5))
  val wdata    = Flipped(UInt(parameter.xlen))
  val clearAll = Flipped(Bool())

@generator
object DitDah32Gpr extends Generator[DitDah32Parameter, DitDah32GprLayers, DitDah32GprIO, DitDah32GprProbe]:

  override def moduleName(parameter: DitDah32Parameter): String = s"DitDah32Gpr_${parameter.hashCode.toHexString}"

  def architecture(parameter: DitDah32Parameter) =
    val io = summon[Interface[DitDah32GprIO]]

    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)

    // x1..x15; x0 has no storage and reads 0.
    val regs = Seq.tabulate(16)(_ => RegInit(0.U(parameter.xlen)))

    Seq((io.raddr1, io.rdata1), (io.raddr2, io.rdata2)).foreach { case (addr, out) =>
      out := 0.U(parameter.xlen)
      (1 to 15).foreach { i =>
        when(addr === i.U(5)) { out := regs(i) }
      }
    }

    when(io.we) {
      (1 to 15).foreach { i =>
        when(io.waddr === i.U(5)) { regs(i) := io.wdata }
      }
    }

    // Debug read port and broadcast clear exist only for the JTAG hart.
    io.rdata3 := 0.U(parameter.xlen)
    if parameter.enableDebug then
      (1 to 15).foreach { i =>
        when(io.raddr3 === i.U(5)) { io.rdata3 := regs(i) }
      }
      when(io.clearAll) {
        (1 to 15).foreach { i =>
          regs(i) := 0.U(parameter.xlen)
        }
      }
