// SPDX-License-Identifier: Apache-2.0
package org.llvm.mlir.scalalib.capi.executionengine

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Module, given}

given ExecutionEngineApi with
  def executionEngineCreate(
    module:           Module,
    optLevel:         Int,
    sharedLibPaths:   Seq[String],
    enableObjectDump: Boolean,
    enablePIC:        Boolean
  )(
    using zone:       Zone
  ): ExecutionEngine =
    require(optLevel >= 0 && optLevel <= 3, "JIT optimization level must be between 0 and 3")
    ExecutionEngine(
      nativeResult[MlirStructs.MlirExecutionEngine](out =>
        mlirApi.mlirExecutionEngineCreate(
          module.native,
          optLevel,
          sharedLibPaths.size,
          sharedLibPaths.map(_.toStringRef).toMlirArray,
          enableObjectDump,
          enablePIC
        )(out)
      )
    )

  extension (engine: ExecutionEngine)

    inline def sizeOf: Int     = sizeof[MlirStructs.MlirExecutionEngine].toInt
    def isNull:        Boolean = (!engine.native).ptr == null
    def destroy():     Unit    = mlirApi.mlirExecutionEngineDestroy(engine.native)
    def initialize():  Unit    = mlirApi.mlirExecutionEngineInitialize(engine.native)
    def invokePacked(
      name:       String,
      arguments:  Seq[Ptr[?]]
    )(
      using zone: Zone
    ): LogicalResult =
      // InvokePacked's missing-symbol path leaves an llvm::Error unchecked in
      // some MLIR versions. LookupPacked consumes that error and returns null.
      if engine.lookupPacked(s"_mlir_ciface_$name") == null then LogicalResult(MlirStructs.MlirLogicalResult(0))
      else
        val pointers = nativeArray(arguments.map(_.asInstanceOf[Ptr[Byte]]))
        LogicalResult(
          nativeResult[MlirStructs.MlirLogicalResult](out =>
            mlirApi.mlirExecutionEngineInvokePacked(engine.native, name.toStringRef.native, pointers)(out)
          )
        )
    def lookup(
      name: String
    )(
      using Zone
    ): Ptr[Byte] =
      mlirApi.mlirExecutionEngineLookup(engine.native, name.toStringRef.native)
    def lookupPacked(
      name: String
    )(
      using Zone
    ): Ptr[Byte] =
      mlirApi.mlirExecutionEngineLookupPacked(engine.native, name.toStringRef.native)
    def registerSymbol(
      name:   String,
      symbol: Ptr[Byte]
    )(
      using Zone
    ): Unit =
      mlirApi.mlirExecutionEngineRegisterSymbol(engine.native, name.toStringRef.native, symbol)
    def dumpToObjectFile(
      fileName: String
    )(
      using Zone
    ): Unit =
      mlirApi.mlirExecutionEngineDumpToObjectFile(engine.native, fileName.toStringRef.native)
