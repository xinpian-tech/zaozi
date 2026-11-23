// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.rewrite

import org.llvm.mlir.raw.structs as MlirStructs
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

class FrozenRewritePatternSet(handle: Ptr[MlirStructs.MlirFrozenRewritePatternSet]) extends NativeHandle(handle)
trait FrozenRewritePatternSetApi                                                    extends HasSizeOf[FrozenRewritePatternSet]

class GreedyRewriteDriverConfig(handle: Ptr[MlirStructs.MlirGreedyRewriteDriverConfig]) extends NativeHandle(handle)
trait GreedyRewriteDriverConfigApi                                                      extends HasSizeOf[GreedyRewriteDriverConfig]

class PDLPatternModule(handle: Ptr[MlirStructs.MlirPDLPatternModule]) extends NativeHandle(handle)
trait PDLPatternModuleApi                                             extends HasSizeOf[PDLPatternModule]

class RewritePatternSet(handle: Ptr[MlirStructs.MlirRewritePatternSet]) extends NativeHandle(handle)
trait RewritePatternSetApi                                              extends HasSizeOf[RewritePatternSet]

class RewriterBase(handle: Ptr[MlirStructs.MlirRewriterBase]) extends NativeHandle(handle)
trait RewriterBaseApi                                         extends HasSizeOf[RewriterBase]
