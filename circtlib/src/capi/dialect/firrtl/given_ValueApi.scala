// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.firrtl

import org.llvm.circt.*
import org.llvm.circt.CAPI.firrtlEmitInvalidate
import org.llvm.mlir.scalalib.capi.ir.{Block, Location, Value, given}

given ValueApi with
  extension (value: Value)
    inline def emitInvalidate(block: Block, loc: Location): Unit =
      firrtlEmitInvalidate(block.segment, loc.segment, value.segment)
end given
