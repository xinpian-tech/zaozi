// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.sparsetensor

import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

class SparseTensorLevelType(val native: org.llvm.mlir.raw.aliases.MlirSparseTensorLevelType)
trait SparseTensorLevelTypeApi
