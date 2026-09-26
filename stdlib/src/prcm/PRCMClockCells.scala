// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import java.lang.foreign.Arena

import me.jiuyang.stdlib.clock.{ClockCell, ClockCellKind, ClockCellParameter}
import org.llvm.circt.scalalib.capi.dialect.hw.given
import org.llvm.circt.scalalib.capi.dialect.seq.given
import org.llvm.mlir.scalalib.capi.ir.{*, given}

private[prcm] object PRCMClockCells:
  def link(
    module: Module,
    cells:  String
  )(
    using Arena,
    Context
  ): Unit =
    val names = ClockCellKind.values.map(kind => ClockCell.verilogModuleName(ClockCellParameter(kind))).toSet
    PRCMStateCut
      .descendants(module.getOperation)
      .filter(_.getName.str == "hw.module.extern")
      .foreach: external =>
        val name        = external.getAttributeByName("sym_name").stringAttrGetValue
        require(names(name), s"unknown PRCM clock cell: $name")
        val verilogName = external.getAttributeByName("verilogName")
        require(
          org.llvm.mlir.MlirAttribute.ptr(verilogName.segment).address() == 0 || verilogName.stringAttrGetValue == name
        )
        val source      = os
          .proc("circt-verilog", "--format=sv", "--ir-hw", s"--top=$name", "--mlir-print-op-generic", "-")
          .call(stdin = cells, stderr = os.Pipe, timeout = 30000)
          .out
          .text()
        val imported    = summon[ModuleApi].moduleCreateParse(source)
        require(org.llvm.mlir.MlirModule.ptr(imported.segment).address() != 0, s"could not import clock cell $name")
        try
          val definitions = PRCMStateCut.children(imported.getOperation).filter(_.getName.str == "hw.module")
          require(definitions.size == 1)
          val definition  = definitions.head
          val interface   = external.getAttributeByName("module_type").typeAttrGetValue
          val original    = definition.getAttributeByName("module_type").typeAttrGetValue
          require(interface.moduleTypeGetNumInputs() == original.moduleTypeGetNumInputs())
          require(interface.moduleTypeGetNumOutputs() == original.moduleTypeGetNumOutputs())
          val body        = definition.getRegion(0).getFirstBlock
          (0 until interface.moduleTypeGetNumInputs()).foreach:  index =>
            require(interface.moduleTypeGetInputName(index) == original.moduleTypeGetInputName(index))
            val expected = Type(interface.moduleTypeGetPort(index).portType)
            if expected.isClock then
              val argument = body.getArgument(index)
              argument.setType(expected)
              val cast     = summon[OperationApi].operationCreate(
                "seq.from_clock",
                definition.getLocation,
                operands = Seq(argument),
                resultsTypes = Some(Seq(1.integerTypeGet))
              )
              argument.replaceAllUsesOfWith(cast.getResult(0))
              cast.setOperand(0, argument)
              body.insertOwnedOperation(0, cast)
          val output = body.getTerminator
          (0 until interface.moduleTypeGetNumOutputs()).foreach: index =>
            require(interface.moduleTypeGetOutputName(index) == original.moduleTypeGetOutputName(index))
            val expected = Type(interface.moduleTypeGetPort(interface.moduleTypeGetNumInputs() + index).portType)
            if expected.isClock then
              val cast = summon[OperationApi].operationCreate(
                "seq.to_clock",
                definition.getLocation,
                operands = Seq(output.getOperand(index)),
                resultsTypes = Some(Seq(expected))
              )
              body.insertOwnedOperationBefore(output, cast)
              output.setOperand(index, cast.getResult(0))
          definition.setAttributeByName("module_type", interface.typeAttrGet)
          definition.removeFromParent()
          module.getBody.insertOwnedOperationBefore(external, definition)
          external.removeFromParent()
          external.destroy()
        finally imported.destroy()
