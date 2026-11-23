// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.diagnostic

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.enumerations as MlirEnums
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.ir.Location
import org.llvm.mlir.scalalib.capi.support.{*, given}

given DiagnosticHandlerApi = new DiagnosticHandlerApi {}

given DiagnosticHandlerIDApi = new DiagnosticHandlerIDApi {}

given DiagnosticEnumApi with
  extension (int: Int)
    inline def fromNative: DiagnosticSeverityEnum = int match
      case i if i == MlirEnums.MlirDiagnosticSeverity.MlirDiagnosticError.int   => DiagnosticSeverityEnum.Error
      case i if i == MlirEnums.MlirDiagnosticSeverity.MlirDiagnosticNote.int    => DiagnosticSeverityEnum.Note
      case i if i == MlirEnums.MlirDiagnosticSeverity.MlirDiagnosticRemark.int  => DiagnosticSeverityEnum.Remark
      case i if i == MlirEnums.MlirDiagnosticSeverity.MlirDiagnosticWarning.int => DiagnosticSeverityEnum.Warning
  extension (ref: DiagnosticSeverityEnum)
    inline def toNative: Int = ref match
      case DiagnosticSeverityEnum.Error   => MlirEnums.MlirDiagnosticSeverity.MlirDiagnosticError.int
      case DiagnosticSeverityEnum.Note    => MlirEnums.MlirDiagnosticSeverity.MlirDiagnosticNote.int
      case DiagnosticSeverityEnum.Remark  => MlirEnums.MlirDiagnosticSeverity.MlirDiagnosticRemark.int
      case DiagnosticSeverityEnum.Warning => MlirEnums.MlirDiagnosticSeverity.MlirDiagnosticWarning.int
    inline def sizeOf:   Int = 4
end given

given DiagnosticApi with
  extension (diagnostic: Diagnostic)
    inline def getLocation(
      using zone: Zone
    ): Location = Location(
      nativeResult[MlirStructs.MlirLocation](out => mlirApi.mlirDiagnosticGetLocation(diagnostic.native)(out))
    )
    inline def print(
      callback:   String => Unit
    )(
      using zone: Zone
    ): Unit =
      callback.stringToStringCallback.use { (fn, data) => mlirApi.mlirDiagnosticPrint(diagnostic.native, fn, data) }

    inline def sizeOf: Int = sizeof[MlirStructs.MlirDiagnostic].toInt
end given
