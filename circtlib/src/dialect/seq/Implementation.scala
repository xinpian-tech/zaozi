// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.seq.operation

import org.llvm.circt.scalalib.capi.dialect.seq.{TypeApi as SeqTypeApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, Location, Operation, OperationApi, Value, given}

import java.lang.foreign.Arena

given ToClockApi with
  def op(input: Value, location: Location)(using Arena, Context): ToClock =
    ToClock(
      summon[OperationApi].operationCreate(
        name = "seq.to_clock",
        location = location,
        operands = Seq(input),
        resultsTypes = Some(Seq(summon[SeqTypeApi].clockTypeGet))
      )
    )
  extension (ref: ToClock)
    def operation: Operation = ref._operation
    def result(using Arena): Value = ref.operation.getResult(0)
end given
