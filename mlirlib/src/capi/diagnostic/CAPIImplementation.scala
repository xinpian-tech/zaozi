// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.diagnostic

import org.llvm.mlir.*
import org.llvm.mlir.CAPI.{
  mlirDiagnosticGetLocation,
  mlirDiagnosticPrint,
  MlirDiagnosticError,
  MlirDiagnosticNote,
  MlirDiagnosticRemark,
  MlirDiagnosticWarning
}
import org.llvm.mlir.scalalib.capi.ir.Location
import org.llvm.mlir.scalalib.capi.support.{*, given}

import java.lang.foreign.{Arena, MemorySegment}

given DiagnosticHandlerApi with
  extension (diagnosticHandler: DiagnosticHandler) inline def segment: MemorySegment = diagnosticHandler._segment
end given

given DiagnosticHandlerIDApi with
  extension (diagnosticHandlerID: DiagnosticHandlerID) inline def segment: MemorySegment = diagnosticHandlerID._segment
end given

given DiagnosticEnumApi with
  extension (int: Int)
    inline def fromNative: DiagnosticSeverityEnum = int match
      case i if i == MlirDiagnosticError()   => DiagnosticSeverityEnum.Error
      case i if i == MlirDiagnosticNote()    => DiagnosticSeverityEnum.Note
      case i if i == MlirDiagnosticRemark()  => DiagnosticSeverityEnum.Remark
      case i if i == MlirDiagnosticWarning() => DiagnosticSeverityEnum.Warning
  extension (ref: DiagnosticSeverityEnum)
    inline def toNative: Int = ref match
      case DiagnosticSeverityEnum.Error   => MlirDiagnosticError()
      case DiagnosticSeverityEnum.Note    => MlirDiagnosticNote()
      case DiagnosticSeverityEnum.Remark  => MlirDiagnosticRemark()
      case DiagnosticSeverityEnum.Warning => MlirDiagnosticWarning()
    inline def sizeOf:   Int = 4
end given

given DiagnosticApi with
  extension (diagnostic: Diagnostic)
    inline def getLocation(
      using arena: Arena
    ): Location = Location(mlirDiagnosticGetLocation(arena, diagnostic.segment))
    inline def print(
      callback:    String => Unit
    )(
      using arena: Arena
    ): Unit =
      mlirDiagnosticPrint(diagnostic.segment, callback.stringToStringCallback.segment, MemorySegment.NULL)
    inline def segment: MemorySegment = diagnostic._segment
    inline def sizeOf:  Int           = MlirDiagnostic.sizeof().toInt
end given
