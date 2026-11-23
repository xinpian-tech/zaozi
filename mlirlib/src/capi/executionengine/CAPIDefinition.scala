// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.executionengine

import org.llvm.mlir.raw.structs as MlirStructs
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.Module

class ExecutionEngine(handle: Ptr[MlirStructs.MlirExecutionEngine]) extends NativeHandle(handle)
trait ExecutionEngineApi                                            extends HasSizeOf[ExecutionEngine]:
  def executionEngineCreate(
    module:           Module,
    optLevel:         Int = 0,
    sharedLibPaths:   Seq[String] = Seq.empty,
    enableObjectDump: Boolean = false,
    enablePIC:        Boolean = false
  )(
    using Zone
  ): ExecutionEngine

  extension (engine: ExecutionEngine)
    def isNull:       Boolean
    def destroy():    Unit
    def initialize(): Unit

    /** Arguments point to native storage; append result storage for non-void functions. The function must have
      * llvm.emit_c_interface. Storage must outlive this call.
      */
    def invokePacked(
      name:      String,
      arguments: Seq[Ptr[?]]
    )(
      using Zone
    ): LogicalResult
    def lookup(
      name: String
    )(
      using Zone
    ): Ptr[Byte]
    def lookupPacked(
      name: String
    )(
      using Zone
    ): Ptr[Byte]

    /** The caller owns the symbol's lifetime, including any upcall zone. */
    def registerSymbol(
      name:   String,
      symbol: Ptr[Byte]
    )(
      using Zone
    ): Unit
    def dumpToObjectFile(
      fileName: String
    )(
      using Zone
    ): Unit
