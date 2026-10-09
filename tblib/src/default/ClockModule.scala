// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.tblib.default

import me.jiuyang.zaozi.{HWApi, SVApi, SeqApi}
import me.jiuyang.zaozi.default.{*, given}
import org.llvm.circt.scalalib.dialect.hw.operation.{Module, ModuleApi, Port, PortDirection, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, LocationApi, TypeApi, given}

import java.lang.foreign.Arena

/** A simulation clock source with one seq.clock output and no inputs. */
private[default] object ClockModule:
  def create(
    periodNs: Long
  )(
    using Arena,
    Context,
    Block
  ): Module =
    require(periodNs > 0 && periodNs % 2 == 0, "clock period must be a positive even number of nanoseconds")
    val module = summon[ModuleApi].op(
      symbol = s"Clock_periodNs$periodNs",
      ports = Seq(Port("clock", PortDirection.Output, summon[SeqApi].clockType)),
      location = summon[LocationApi].locationUnknownGet
    )
    module.operation.appendToBlock()
    locally:
      given Block = module.block
      val clock   = summon[SVApi].reg(1.integerTypeGet, "clock_reg")
      summon[SVApi].verbatim(
        s"initial {{0}} = 1'b0;\nalways #${periodNs / 2}ns {{0}} = ~{{0}};",
        Seq(clock)
      )
      summon[HWApi].output(Seq(clock.readInOut.toClock))
    module
