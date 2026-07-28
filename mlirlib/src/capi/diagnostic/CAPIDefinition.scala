// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.diagnostic

import org.llvm.mlir.scalalib.capi.ir.Location
import org.llvm.mlir.scalalib.capi.support.{*, given}

import java.lang.foreign.{Arena, MemorySegment}

class Diagnostic(val _segment: MemorySegment)
trait DiagnosticApi extends HasSegment[Diagnostic] with HasSizeOf[Diagnostic]:
  extension (diagnostic: Diagnostic)
    inline def getLocation(
      using arena: Arena
    ): Location
    inline def print(
      callback:    String => Unit
    )(
      using arena: Arena
    ): Unit
end DiagnosticApi

class DiagnosticHandler(val _segment: MemorySegment)
trait DiagnosticHandlerApi extends HasSegment[DiagnosticHandler]

class DiagnosticHandlerID(val _segment: MemorySegment)
trait DiagnosticHandlerIDApi extends HasSegment[DiagnosticHandlerID]

enum DiagnosticSeverityEnum:
  case Error
  case Note
  case Remark
  case Warning
end DiagnosticSeverityEnum
trait DiagnosticEnumApi extends HasSizeOf[DiagnosticSeverityEnum] with EnumHasToNative[DiagnosticSeverityEnum]
