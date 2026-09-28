package com.vowstar.ditdah32

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.ClockEvent
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.syntheke.demo.zaoziimpl.{RetentionCell, RetentionBundle, RetentionP}

class DitDah32GprLayers(parameter: DitDah32Parameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class DitDah32GprProbe(parameter: DitDah32Parameter) extends DVBundle[DitDah32Parameter, DitDah32GprLayers](parameter)

class DitDah32GprIO(parameter: DitDah32Parameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val retention = Flipped(new RetentionBundle)

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

    val save = io.retention.save & !io.reset.asBool & !io.retention.restore
    val restore = io.retention.restore & !io.reset.asBool & !io.retention.save
    val retained = (1 to 15).map { i =>
      val cell = RetentionCell.instantiate(
        RetentionP(parameter.xlen, parameter.cpuMillivolts, parameter.retentionMillivolts)
      )
      cell.io.clock := io.clock
      cell.io.reset := io.retention.reset
      cell.io.in := regs(i).asBits
      cell.io.save := save
      cell.io.restore := restore
      when(restore) { regs(i) := cell.io.out.asUInt }
      (regs(i), cell)
    }
    io.retention.saved := save & retained.map(entry => Node(entry._2.io.saved)).reduce(_ & _)
    io.retention.restored := restore & retained.map(entry => Node(entry._2.io.restored)).reduce(_ & _)

    layer("Verification"):
      given ClockEvent = posedge(io.clock)
      val restoredValues = retained.map { (reg, cell) => reg.asBits === cell.io.out }.reduce(_ & _)
      Assert((!io.retention.restored | restoredValues).S, !io.reset.asBool, "retention_gpr_restored")
