// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.diagnostic

import org.llvm.mlir.raw.structs as MlirStructs
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.ir.Location
import org.llvm.mlir.scalalib.capi.support.{*, given}

class Diagnostic(handle: Ptr[MlirStructs.MlirDiagnostic]) extends NativeHandle(handle)
trait DiagnosticApi                                       extends HasSizeOf[Diagnostic]:
  extension (diagnostic: Diagnostic)
    inline def getLocation(
      using zone: Zone
    ): Location
    inline def print(
      callback:   String => Unit
    )(
      using zone: Zone
    ): Unit
end DiagnosticApi

class DiagnosticHandler(val native: org.llvm.mlir.raw.aliases.MlirDiagnosticHandler)
trait DiagnosticHandlerApi

class DiagnosticHandlerID(val native: org.llvm.mlir.raw.aliases.MlirDiagnosticHandlerID)
trait DiagnosticHandlerIDApi

enum DiagnosticSeverityEnum:
  case Error
  case Note
  case Remark
  case Warning
end DiagnosticSeverityEnum
trait DiagnosticEnumApi extends HasSizeOf[DiagnosticSeverityEnum] with EnumHasToNative[DiagnosticSeverityEnum]
