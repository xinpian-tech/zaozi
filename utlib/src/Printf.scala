// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.dialect.firrtl.operation.{given_PrintfApi, PrintfApi}
import org.llvm.mlir.scalalib.capi.ir.{
  given_BlockApi,
  given_LocationApi,
  given_OperationApi,
  Block,
  Context,
  LocationApi
}

import java.lang.foreign.Arena

/** A simulation `printf`, usable directly from the Zaozi DSL.
  *
  * Zaozi surfaces no print statement of its own, so a design could previously only be traced by injecting `sim` ops
  * into the *lowered* HW module — which meant identifying signals positionally and labelling them by string. Emitting
  * `firrtl.printf` during elaboration instead means the traced values are ordinary typed references: `dut.io.enq.bits`
  * and `dut.probe.enqFire` rather than "operand #3".
  *
  * Note where this does *not* go: in CIRCT 1.147 firtool lowers `firrtl.printf` straight to `sv.fwrite` during
  * `lowFIRRTLToHW`, so a design-level trace does not travel through the `sim` dialect. That is a deliberate trade —
  * typed signal references are worth more here than dialect purity — and the sim dialect is still used for what only it
  * can express (see [[SimInstrument]], which injects `sim.clocked_terminate`).
  *
  * `format` uses FIRRTL's printf syntax (`%d`, `%x`, `%b`), with one substitution per specifier.
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
      // zaozi's own `locate` is package-private, so the source location is
      // rebuilt here the same way.
      location = summon[LocationApi].locationFileLineColGet(
        summon[sourcecode.File].value,
        summon[sourcecode.Line].value,
        0
      )
    )
    .operation
    .appendToBlock()
