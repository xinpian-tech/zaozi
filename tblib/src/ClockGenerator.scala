// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.tblib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface

import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.seq.{DialectApi as SeqDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.sv.{DialectApi as SVDialectApi, given}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{ConnectApi, given}
import org.llvm.mlir.scalalib.capi.ir.{OperationApi, given}

case class ClockParameter(periodNs: Long) extends Parameter:
  require(periodNs > 0 && periodNs % 2 == 0, "clock period must be a positive even number of nanoseconds")

given upickle.default.ReadWriter[ClockParameter] = upickle.default.macroRW

class ClockLayers(parameter: ClockParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ClockIO(parameter: ClockParameter) extends HWBundle(parameter):
  val clock = Aligned(summon[ConstructorApi].Clock())

class ClockProbe(parameter: ClockParameter) extends DVBundle[ClockParameter, ClockLayers](parameter)

/** A simulation clock source instantiated through the ordinary FIRRTL Generator API. */
@generator
object Clock extends Generator[ClockParameter, ClockLayers, ClockIO, ClockProbe]:
  override def moduleName(parameter: ClockParameter): String = s"Clock_periodNs${parameter.periodNs}"

  def architecture(parameter: ClockParameter) =
    val io = summon[Interface[ClockIO]]
    summon[HWDialectApi].loadDialect
    summon[SeqDialectApi].loadDialect
    summon[SVDialectApi].loadDialect

    val sv    = summon[SVApi]
    val clock = sv.reg(1.integerTypeGet, "clock_reg")
    sv.initial:
      sv.blockingAssign(clock, summon[HWApi].constant(0, 1))
    // The SV dialect has no delay operation, so emit the timed statement here.
    sv.verbatim(s"always #${parameter.periodNs / 2}ns {{0}} = ~{{0}};", Seq(clock))
    val seqClock = clock.readInOut.toClock

    // FIRRTLToHW eliminates this bridge and uses the underlying seq.clock value.
    val cast = summon[OperationApi].operationCreate(
      name = "builtin.unrealized_conversion_cast",
      location = locate,
      operands = Seq(seqClock),
      resultsTypes = Some(Seq(io.clock.refer.getType))
    )
    cast.appendToBlock()
    summon[ConnectApi].op(src = cast.getResult(0), dst = io.clock.refer, location = locate)
      .operation.appendToBlock()
