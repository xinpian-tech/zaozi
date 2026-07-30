// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.sim

import org.llvm.mlir.scalalib.capi.ir.{Context, Type, TypeApi as MlirTypeApi, given}

import java.lang.foreign.Arena

given TypeApi with
  def formatStringTypeGet(
    using Arena,
    Context
  ): Type = summon[MlirTypeApi].typeParseGet("!sim.fstring")

  def outputStreamTypeGet(
    using Arena,
    Context
  ): Type = summon[MlirTypeApi].typeParseGet("!sim.output_stream")
end given
