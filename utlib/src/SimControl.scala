// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.dialect.firrtl.operation.{given_PrintfApi, given_StopApi, PrintfApi, StopApi}
import org.llvm.mlir.scalalib.capi.ir.{
  given_BlockApi,
  given_LocationApi,
  given_OperationApi,
  Block,
  Context,
  LocationApi
}

import java.lang.foreign.Arena

/** Simulation control emitted from the Zaozi DSL: `printf` for output and `stop` for
  * termination.
  *
  * Both are ordinary FIRRTL statements over typed references, so a testbench built in the DSL
  * needs no hand-written SystemVerilog: firtool lowers `firrtl.printf` and `firrtl.stop`
  * through the `sim` dialect to the `always @(posedge clock)` / `$fwrite` / `$finish`
  * constructs a real SV testbench uses.
  */

/** A simulation `printf`. `format` uses FIRRTL's printf syntax (`%d`, `%x`, `%b`), one
  * substitution per specifier.
  */
def printf(
  clock:  Referable[Clock],
  cond:   Referable[Bool],
  format: String,
  args:   Referable[?]*
)(
  using Arena,
  Context,
  Block,
  TypeImpl,
  sourcecode.File,
  sourcecode.Line,
  sourcecode.Name.Machine,
  InstanceContext
): Unit =
  summon[PrintfApi]
    .op(
      clock = clock.refer,
      cond = cond.refer,
      formatString = format,
      substitutions = args.map(_.refer),
      name = summon[sourcecode.Name.Machine].value,
      location = summon[LocationApi].locationFileLineColGet(
        summon[sourcecode.File].value,
        summon[sourcecode.Line].value,
        0
      )
    )
    .operation
    .appendToBlock()

/** End the simulation on the rising edge of `clock` when `cond` is high: `$finish` when
  * `exitCode` is zero, `$fatal` otherwise.
  */
def stop(
  clock:    Referable[Clock],
  cond:     Referable[Bool],
  exitCode: Int
)(
  using Arena,
  Context,
  Block,
  TypeImpl,
  sourcecode.File,
  sourcecode.Line,
  sourcecode.Name.Machine,
  InstanceContext
): Unit =
  summon[StopApi]
    .op(
      clock = clock.refer,
      cond = cond.refer,
      exitCode = exitCode,
      name = summon[sourcecode.Name.Machine].value,
      location = summon[LocationApi].locationFileLineColGet(
        summon[sourcecode.File].value,
        summon[sourcecode.Line].value,
        0
      )
    )
    .operation
    .appendToBlock()
