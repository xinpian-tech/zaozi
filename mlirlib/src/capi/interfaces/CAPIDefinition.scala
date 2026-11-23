// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.interfaces

import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

class ShapedTypeComponentsCallback(val native: org.llvm.mlir.raw.aliases.MlirShapedTypeComponentsCallback)
trait ShapedTypeComponentsCallbackApi
