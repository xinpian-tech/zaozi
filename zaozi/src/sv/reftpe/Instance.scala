// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.reftpe

import me.jiuyang.zaozi.sv.{Interface, ParameterInterface, SVInterface, VerilogParameterInterface}
import org.llvm.mlir.scalalib.capi.ir.Operation

final class Instance[I <: SVInterface, V <: VerilogParameterInterface] private[sv] (
  val io:         Interface[I],
  val parameters: ParameterInterface[V],
  val operation:  Operation)

final class FIRRTLInstance[I <: me.jiuyang.zaozi.HWInterface[?]] private[sv] (
  val io:        me.jiuyang.zaozi.reftpe.Interface[I],
  val operation: Operation)
