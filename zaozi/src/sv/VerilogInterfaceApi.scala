// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv

import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.sv.valuetpe.Data
import me.jiuyang.zaozi.sv.reftpe.{Node, Referable}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import java.lang.foreign.Arena

trait VerilogInterfaceApi:
  def InterfaceInstance[I <: VerilogInterface](
    definition: I
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    SVElaborationContext,
    InstanceContext,
    sourcecode.Name.Machine,
    sourcecode.File,
    sourcecode.Line
  ):   Node[I]
  extension [T <: Data](dest: InterfaceSignalRef[T])
    def :=(
      value: Referable[T]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Unit
