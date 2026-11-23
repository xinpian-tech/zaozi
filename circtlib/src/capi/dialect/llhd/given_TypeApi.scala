// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.llhd

import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.ir.{Type, given}

given TypeApi with
  extension (tpe: Type) inline def isTimeType: Boolean = circtApi.llhdTypeIsATimeType(tpe.native)
end given
