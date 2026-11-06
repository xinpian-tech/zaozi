// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.default

import me.jiuyang.zaozi.sv.valuetpe.Data
import me.jiuyang.zaozi.sv.reftpe.Node
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import java.lang.foreign.Arena

private[sv] def emitted[T <: Data](
  tpe:       T,
  operation: Operation
)(
  using Arena,
  Block
): Node[T] =
  operation.appendToBlock()
  new Node[T]:
    private[zaozi] val _tpe   = tpe
    private[zaozi] val _refer = operation.getResult(0)

private[sv] def procedural(
  block: Block
)(
  using Arena
): Boolean =
  var parent = block.getParentOperation
  while org.llvm.mlir.MlirOperation.ptr(parent.segment) != java.lang.foreign.MemorySegment.NULL do
    parent.getName.str match
      case "sv.initial" | "sv.always" | "sv.alwayscomb" | "sv.alwaysff" | "sv.func" => return true
      case _                                                                        => parent = parent.getParentOperation
  false
