// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>
package me.jiuyang.stdlib.dwbb

import scala.util.chaining.*

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

// DW_fifo_s1_sf
case class DwbbFifoParameter(
  width:            Int,
  entries:          Int,
  pipe:             Boolean = false,
  flow:             Boolean = false,
  useAsyncReset:    Boolean = false,
  resetMem:         Boolean = false,
  almostEmptyLevel: Int = 1,
  almostFullLevel:  Int = 1)
    extends Parameter
given upickle.default.ReadWriter[DwbbFifoParameter] = upickle.default.macroRW

class DwbbFifoIO(
  parameter: DwbbFifoParameter)
    extends HWBundle(parameter):
  val clk          = Flipped(Clock())
  val rst_n        = Flipped(Bool())
  val push_req_n   = Flipped(Bool())
  val pop_req_n    = Flipped(Bool())
  val diag_n       = Flipped(Bool())
  val data_in      = Flipped(Bits(parameter.width))
  val empty        = Aligned(Bool())
  val almost_empty = Aligned(Bool())
  val half_full    = Aligned(Bool())
  val almost_full  = Aligned(Bool())
  val full         = Aligned(Bool())
  val error        = Aligned(Bool())
  val data_out     = Aligned(Bits(parameter.width))

class DwbbFifoLayers(parameter: DwbbFifoParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class DwbbFifoProbe(parameter: DwbbFifoParameter) extends DVBundle[DwbbFifoParameter, DwbbFifoLayers](parameter)

case class DwbbFifoVerilogParams(
  width:    Int,
  depth:    Int,
  ae_level: Int,
  af_level: Int,
  err_mode: Int,
  rst_mode: Int)
    extends VerilogParameter

@generator
object DwbbFifo
    extends VerilogWrapper[DwbbFifoParameter, DwbbFifoLayers, DwbbFifoIO, DwbbFifoProbe, DwbbFifoVerilogParams]:
  def verilogModuleName(parameter: DwbbFifoParameter) = "DW_fifo_s1_sf"
  def verilogParameter(parameter: DwbbFifoParameter)  = DwbbFifoVerilogParams(
    width = parameter.width,
    depth = parameter.entries,
    ae_level = parameter.almostEmptyLevel,
    af_level = parameter.almostFullLevel,
    err_mode = 2,
    rst_mode = (parameter.useAsyncReset, parameter.resetMem) match
      case (false, false) => 3
      case (false, true)  => 1
      case (true, false)  => 2
      case (true, true)   => 0
  )

given QueueImpl with
  def apply[D <: HardwareDataType](
    parameter: QueueParameter[D]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Wire[QueueIO[D]] =
    val fifo    = DwbbFifo.instantiate(
      DwbbFifoParameter(
        parameter.gen.width,
        parameter.entries,
        parameter.pipe,
        parameter.flow,
        parameter.useAsyncReset,
        parameter.resetMem,
        parameter.almostEmptyLevel,
        parameter.almostFullLevel
      )
    )
    val io      = Wire(new QueueIO(parameter))
    val dataIn  = io.enq.bits.asBits
    val dataOut = fifo.io.data_out.asType(io.deq.bits.getType)

    fifo.io.clk    := io.clock
    fifo.io.rst_n  := !(io.reset.asBool)
    fifo.io.diag_n := !(false.B)

    io.enq.ready       := !fifo.io.full | (if (parameter.pipe) io.deq.ready else false.B)
    fifo.io.push_req_n := !(io.enq.fire & (if (parameter.flow) !(fifo.io.empty & io.deq.ready) else true.B))
    fifo.io.data_in    := dataIn

    io.deq.valid      := !fifo.io.empty | (if (parameter.flow) io.enq.valid else false.B)
    fifo.io.pop_req_n := !(io.deq.ready & !fifo.io.empty)
    io.deq.bits       := (if (parameter.flow) (fifo.io.empty ? (io.enq.bits, dataOut)) else dataOut)

    io.empty := fifo.io.empty
    io.full  := fifo.io.full
    io.almostEmpty.foreach(_ := fifo.io.almost_empty)
    io.almostFull.foreach(_ := fifo.io.almost_full)

    io

end given
