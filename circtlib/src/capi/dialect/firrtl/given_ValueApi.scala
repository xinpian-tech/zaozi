// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.firrtl

import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Location, Value, given}

given ValueApi with
  extension (value: Value)
    inline def emitInvalidate(block: Block, loc: Location): Unit =
      circtApi.firrtlEmitInvalidate(block.native, loc.native, value.native)
end given
