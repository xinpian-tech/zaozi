// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.transform

import org.llvm.mlir.raw.structs as MlirStructs
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

class TransformOptions(handle: Ptr[MlirStructs.MlirTransformOptions]) extends NativeHandle(handle)
trait TransformOptionsApi                                             extends HasSizeOf[TransformOptions]
