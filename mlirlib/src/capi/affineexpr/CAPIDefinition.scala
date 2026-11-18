// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.affineexpr

import org.llvm.mlir.raw.structs as MlirStructs
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

class AffineExpr(handle: Ptr[MlirStructs.MlirAffineExpr]) extends NativeHandle(handle)
trait AffineExprApi                                       extends HasSizeOf[AffineExpr]
