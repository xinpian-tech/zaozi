// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.default

import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.default.{locate, valName}
import me.jiuyang.zaozi.sv.*
import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.{Node, Referable}
import org.llvm.circt.scalalib.dialect.sv.operation.{InterfaceApi as NativeInterfaceApi, *, given}
import org.llvm.circt.scalalib.dialect.sv.attr.{AttributeApi as NativeAttributes, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import java.lang.foreign.Arena

given VerilogInterfaceApi with
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
  ): Node[I] =
    declareInterface(definition)
    emitted(
      definition,
      summon[InterfaceInstanceApi].op(valName, resultType = definition.toMlirType, location = locate).operation
    )
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
    ): Unit =
      val inProcedure = procedural(summon[Block])
      if dest.modport.nonEmpty || inProcedure then
        summon[VerbatimApi]
          .op(
            "assign {{0}}." + dest.signal.name + " = {{1}};",
            Seq(dest.interfaceValue, value.refer),
            Seq.empty,
            locate
          )
          .operation
          .appendToBlock()
      else
        summon[AssignInterfaceSignalApi]
          .op(dest.interfaceValue, dest.signal.name, value.refer, locate)
          .operation
          .appendToBlock()

private[sv] def declareInterface(
  tpe: Data
)(
  using Arena,
  Context,
  TypeImpl,
  SVElaborationContext
): Unit =
  tpe match
    case port:       Modport[?]       => declareInterface(port.interface)
    case definition: VerilogInterface =>
      val root = summon[SVElaborationContext]
      if root.modules.add(definition.name) then
        val loc   = summon[LocationApi].locationUnknownGet
        val op    = summon[NativeInterfaceApi].op(definition.name, location = loc)
        op.operation.appendToBlock()(
          using root.module.getBody
        )
        val scope = new TypeScopeContext(definition.name + "_types")
        definition.signals.foreach:  field =>
          scope.use(field.dataType)
          summon[InterfaceSignalApi]
            .op(field.name, tpe = field.dataType.toMlirType, location = loc)
            .operation
            .appendToBlock()(
              using op.block
            )
        definition.modports.foreach: port =>
          val fields = port.ports.map(f => summon[NativeAttributes].getModportStruct(f.direction, f.signal.name))
          summon[InterfaceModportApi]
            .op(port.name, ports = fields, location = loc)
            .operation
            .appendToBlock()(
              using op.block
            )
        emitTypeDeclarations(scope, root.module.getBody)
    case _ => ()
