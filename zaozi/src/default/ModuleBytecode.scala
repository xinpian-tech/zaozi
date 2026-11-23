// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.zaozi.default

import org.llvm.mlir.scalalib.capi.ir.{given_ModuleApi, given_OperationApi, Module}

import org.llvm.mlir.raw.structs.MlirOperation
import scala.scalanative.unsafe.{extern, name, toCString, CString, Ptr, Zone}

@extern
private object BytecodeFile:
  @name("zaozi_write_bytecode_file")
  def write(operation: Ptr[MlirOperation], path: CString): Int = extern

private object ModuleBytecode:
  def write(
    module: Module,
    file:   os.Path
  )(
    using Zone
  ): Unit =
    val error = BytecodeFile.write(module.getOperation.native, toCString(file.toString))
    if error != 0 then throw new java.io.IOException(s"Cannot write $file (errno $error)")
