// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.executionengine

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.Module
import java.lang.foreign.{Arena, MemorySegment}

class ExecutionEngine(val _segment: MemorySegment)
trait ExecutionEngineApi extends HasSegment[ExecutionEngine] with HasSizeOf[ExecutionEngine]:
  def executionEngineCreate(
    module:           Module,
    optLevel:         Int = 0,
    sharedLibPaths:   Seq[String] = Seq.empty,
    enableObjectDump: Boolean = false,
    enablePIC:        Boolean = false
  )(
    using Arena
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
      arguments: Seq[MemorySegment]
    )(
      using Arena
    ): LogicalResult
    def lookup(
      name: String
    )(
      using Arena
    ): MemorySegment
    def lookupPacked(
      name: String
    )(
      using Arena
    ): MemorySegment

    /** The caller owns the symbol's lifetime, including any upcall arena. */
    def registerSymbol(
      name:   String,
      symbol: MemorySegment
    )(
      using Arena
    ): Unit
    def dumpToObjectFile(
      fileName: String
    )(
      using Arena
    ): Unit
