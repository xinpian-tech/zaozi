// SPDX-License-Identifier: Apache-2.0
package org.llvm.mlir.scalalib.capi.executionengine

import org.llvm.mlir.{CAPI, MlirExecutionEngine, MlirLogicalResult}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Module, given}
import java.lang.foreign.{Arena, MemorySegment, ValueLayout}

given ExecutionEngineApi with
  def executionEngineCreate(
    module:           Module,
    optLevel:         Int,
    sharedLibPaths:   Seq[String],
    enableObjectDump: Boolean,
    enablePIC:        Boolean
  )(
    using arena:      Arena
  ): ExecutionEngine =
    require(optLevel >= 0 && optLevel <= 3, "JIT optimization level must be between 0 and 3")
    ExecutionEngine(
      CAPI.mlirExecutionEngineCreate(
        arena,
        module.segment,
        optLevel,
        sharedLibPaths.size,
        sharedLibPaths.map(_.toStringRef).toMlirArray,
        enableObjectDump,
        enablePIC
      )
    )

  extension (engine: ExecutionEngine)
    inline def segment: MemorySegment = engine._segment
    inline def sizeOf:  Int           = MlirExecutionEngine.sizeof().toInt
    def isNull:         Boolean       = MlirExecutionEngine.ptr(engine.segment).address() == 0
    def destroy():      Unit          = CAPI.mlirExecutionEngineDestroy(engine.segment)
    def initialize():   Unit          = CAPI.mlirExecutionEngineInitialize(engine.segment)
    def invokePacked(
      name:        String,
      arguments:   Seq[MemorySegment]
    )(
      using arena: Arena
    ): LogicalResult =
      // InvokePacked's missing-symbol path leaves an llvm::Error unchecked in
      // some MLIR versions. LookupPacked consumes that error and returns null.
      if engine.lookupPacked(s"_mlir_ciface_$name").address() == 0 then LogicalResult(MlirLogicalResult.allocate(arena))
      else
        val pointers =
          if arguments.isEmpty then MemorySegment.NULL else arena.allocate(ValueLayout.ADDRESS, arguments.size)
        arguments.zipWithIndex.foreach((argument, index) => pointers.setAtIndex(ValueLayout.ADDRESS, index, argument))
        LogicalResult(CAPI.mlirExecutionEngineInvokePacked(arena, engine.segment, name.toStringRef.segment, pointers))
    def lookup(
      name: String
    )(
      using Arena
    ): MemorySegment =
      CAPI.mlirExecutionEngineLookup(engine.segment, name.toStringRef.segment)
    def lookupPacked(
      name: String
    )(
      using Arena
    ): MemorySegment =
      CAPI.mlirExecutionEngineLookupPacked(engine.segment, name.toStringRef.segment)
    def registerSymbol(
      name:   String,
      symbol: MemorySegment
    )(
      using Arena
    ): Unit =
      CAPI.mlirExecutionEngineRegisterSymbol(engine.segment, name.toStringRef.segment, symbol)
    def dumpToObjectFile(
      fileName: String
    )(
      using Arena
    ): Unit =
      CAPI.mlirExecutionEngineDumpToObjectFile(engine.segment, fileName.toStringRef.segment)
