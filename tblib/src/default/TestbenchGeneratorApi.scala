// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.tblib.default

import me.jiuyang.tblib.{
  Clock,
  ClockParameter,
  Testbench,
  TestbenchGenerator,
  TestbenchGeneratorApi
}
import me.jiuyang.zaozi.{DVInterface, HWInterface, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{*, given}

import org.llvm.circt.CAPI
import org.llvm.circt.scalalib.capi.dialect.firrtl.{DialectApi as FIRRTLDialectApi, LinkCircuitsPassApi, given}
import org.llvm.circt.scalalib.capi.dialect.emit.{DialectApi as EmitDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.hw.{
  DialectApi as HWDialectApi,
  HWModulePort,
  TypeApi as HWTypeApi,
  given
}
import org.llvm.circt.scalalib.capi.dialect.comb.{DialectApi as CombDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.ltl.{DialectApi as LTLDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.seq.{DialectApi as SeqDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.sim.{DPIArgumentApi, DPIDirection, DialectApi as SimDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.sv.{DialectApi as SVDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.verif.{DialectApi as VerifDialectApi, given}
import org.llvm.circt.scalalib.capi.firtool.{FirtoolApi, given}
import org.llvm.circt.scalalib.dialect.hw.operation.{Module as HWModule, PortDirection, given}
import org.llvm.circt.scalalib.dialect.sim.operation.{DPIFuncApi, given}
import org.llvm.mlir.scalalib.capi.ir.{
  Attribute,
  Block,
  Context,
  LocationApi,
  Module,
  ModuleApi,
  Operation,
  OperationApi,
  SymbolTableApi,
  Type,
  given
}
import org.llvm.mlir.scalalib.capi.pass.{PassManagerApi, given}
import org.llvm.mlir.scalalib.capi.support.given_LogicalResultApi
import org.llvm.mlir.{MlirAttribute, MlirModule, MlirOperation}

import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import scala.annotation.tailrec
import scala.util.control.NonFatal

given TestbenchGeneratorApi with
  private def operationIsNull(operation: Operation): Boolean = MlirOperation.ptr(operation.segment).address == 0

  @tailrec
  private def moveOperationsToBlock(
    operation:   Operation,
    destination: Block
  )(
    using Arena
  ): Unit =
    if !operationIsNull(operation) then
      val next = operation.getNextInBlock
      operation.removeFromParent()
      destination.appendOwnedOperation(operation)
      moveOperationsToBlock(next, destination)

  private def findHWModule(
    module: Module,
    symbol: String
  )(
    using Arena
  ): HWModule =
    val symbolTable = summon[SymbolTableApi].symbolTableCreate(module.getOperation)
    try
      val operation = symbolTable.lookup(symbol)
      require(!operationIsNull(operation), s"lowered HW module not found: $symbol")
      require(operation.getName.str == "hw.module", s"lowered symbol is not an HW module: $symbol")
      new HWModule(operation)
    finally symbolTable.destroy()

  private def linkFIRRTLCircuits(
    module:         Module,
    topCircuitName: String
  )(
    using Arena,
    Context
  ): Unit =
    val pm = summon[PassManagerApi].passManagerCreate
    try
      pm.addOwnedPass(summon[LinkCircuitsPassApi].createLinkCircuitsPass(topCircuitName, noMangle = true))
      pm.runOnOpOrThrow(module.getOperation, s"FIRRTL linking for top circuit '$topCircuitName'")
    finally pm.destroy()

  private def lowerFIRRTLToHW(
    module:        Module,
    testbenchName: String
  )(
    using Arena,
    Context
  ): Unit =
    val firtoolOptions = summon[FirtoolApi].firtoolOptionsCreateDefault
    try
      val pm = summon[PassManagerApi].passManagerCreate
      try
        pm.preprocessTransforms(firtoolOptions)
        pm.chirrtlToLowFIRRTL(firtoolOptions)
        pm.lowFIRRTLToHW(firtoolOptions, s"$testbenchName.mlirbc")
        pm.runOnOpOrThrow(
          module.getOperation,
          s"FIRRTL to HW lowering for unit testbench '$testbenchName'"
        )
      finally pm.destroy()
    finally firtoolOptions.destroy

  extension [PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    generator: TestbenchGenerator[PARAM, L, I, P]
  )
    def module(
      parameter:     PARAM,
      firrtlModules: Seq[Array[Byte]]
    )(
      using Arena,
      Context
    ): Module =
      require(firrtlModules.nonEmpty, "unit-test construction requires at least one FIRRTL module")
      val testbenchName = generator.moduleName(parameter)

      summon[FIRRTLDialectApi].loadDialect
      summon[EmitDialectApi].loadDialect
      summon[HWDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[SeqDialectApi].loadDialect
      summon[SimDialectApi].loadDialect
      summon[SVDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect

      val module = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
      try
        firrtlModules.zipWithIndex.foreach: (bytecode, index) =>
          val source = summon[ModuleApi].moduleCreateParse(bytecode)
          require(MlirModule.ptr(source.segment).address != 0, s"failed to parse FIRRTL input $index")
          try moveOperationsToBlock(source.getBody.getFirstOperation, module.getBody)
          finally source.destroy()

        linkFIRRTLCircuits(module, testbenchName)
        lowerFIRRTLToHW(module, testbenchName)
        require(module.getOperation.verify, "invalid lowered unit under test")
        val testbenchModule = findHWModule(module, testbenchName)
        val hwPorts         = testbenchModule.ports
        val interface       = generator.interface(parameter)
        interface.toMlirType
        val fields          = interface.elements
        require(fields.forall(_.isFlipped), "testbench IO must contain only inputs; observe DUT signals through Probe")
        require(
          hwPorts.size == fields.size && hwPorts
            .zip(fields)
            .forall: (port, field) =>
              port.name == field.name &&
                port.direction == PortDirection.Input,
          "lowered HW ports do not match the testbench interface"
        )
        given Block = testbenchModule.block
        summon[CombDialectApi].loadDialect
        val block           = summon[Block]
        val clockModuleName = Clock.moduleName(ClockParameter(generator.clockPeriodNs(parameter)))
        val clockInstance   = Iterator
          .iterate(block.getFirstOperation)(_.getNextInBlock)
          .takeWhile(operation => !operationIsNull(operation))
          .find(operation =>
            operation.getName.str == "hw.instance" &&
              operation.getInherentAttributeByName("moduleName").flatSymbolRefAttrGetValue == clockModuleName
          )
          .getOrElse(throw new IllegalStateException("testbench clock instance is missing"))
        val arguments       = hwPorts.indices.map(index => block.getArgument(index.toLong))
        val terminator      = block.getTerminator
        given testbench: Testbench[I] = new DefaultTestbench[I](clockInstance.getResult(0), fields, hwPorts)
        generator.simulation(parameter)

        // Simulation APIs append to the module block. Move their operations before hw.output.
        var appended = terminator.getNextInBlock
        while !operationIsNull(appended) do
          val next = appended.getNextInBlock
          appended.moveBefore(terminator)
          appended = next

        arguments.zip(testbench.inputValues).foreach((argument, value) => argument.replaceAllUsesOfWith(value))
        hwPorts.indices.reverse.foreach(block.eraseArgument)
        testbenchModule.operation.setInherentAttributeByName(
          "module_type",
          summon[HWTypeApi].moduleTypeGet(0, Seq.empty[HWModulePort]).typeAttrGet
        )
        testbenchModule.operation.setInherentAttributeByName("per_port_attrs", Seq.empty[Attribute].arrayAttrGet)
        testbenchModule.operation.setInherentAttributeByName("result_locs", Seq.empty[Attribute].arrayAttrGet)

        require(module.getOperation.verify, "invalid unit testbench")
        module
      catch
        case NonFatal(exception) =>
          module.destroy()
          throw exception

  extension (module: Module)
    def toMlirBytecode(
      using Arena
    ): Array[Byte] =
      val bytecode = new ByteArrayOutputStream
      module.getOperation.writeBytecode(bytes => bytecode.write(bytes))
      bytecode.toByteArray

    def toDpiJson(
      using Arena
    ): ujson.Value =
      given Context = module.getContext
      summon[SimDialectApi].loadDialect
      val copy = summon[OperationApi].operationClone(module.getOperation)
      try
        val exportModule = summon[ModuleApi].moduleFromOperation(copy)
        val symbolTable = summon[SymbolTableApi].symbolTableCreate(copy)
        try
          // The current native exporter accepts Sim declarations. Adapt SV imports in this copy only.
          Iterator
            .iterate(module.getBody.getFirstOperation)(_.getNextInBlock)
            .takeWhile(operation => !operationIsNull(operation))
            .filter(_.getName.str == "sv.func.dpi.import")
            .zipWithIndex
            .foreach: (dpiImport, functionIndex) =>
              val callee = dpiImport.getInherentAttributeByName("callee").flatSymbolRefAttrGetValue
              val function = symbolTable.lookup(callee)
              require(
                !operationIsNull(function) && function.getName.str == "sv.func",
                s"DPI function not found: $callee"
              )
              val linkageName = dpiImport.getInherentAttributeByName("linkage_name")
              val verilogName = function.getInherentAttributeByName("verilogName")
              val name =
                if MlirAttribute.ptr(linkageName.segment).address != 0 then linkageName.stringAttrGetValue
                else if MlirAttribute.ptr(verilogName.segment).address != 0 then verilogName.stringAttrGetValue
                else callee
              val tpe = function.getInherentAttributeByName("module_type").typeAttrGetValue
              val argumentAttrs = function.getInherentAttributeByName("per_argument_attrs")
              val portCount = tpe.moduleTypeGetNumInputs() + tpe.moduleTypeGetNumOutputs()
              val arguments = (0 until portCount).map: index =>
                val port = tpe.moduleTypeGetPort(index)
                val isReturn = index == portCount - 1 && MlirAttribute.ptr(argumentAttrs.segment).address != 0 &&
                  MlirAttribute.ptr(
                    argumentAttrs.arrayAttrGetElement(index)
                      .dictionaryAttrGetElementByName("sv.func.explicitly_returned").segment
                  ).address != 0
                val direction = port.portDirection match
                  case value if value == CAPI.Input()  => DPIDirection.In
                  case value if value == CAPI.Output() => if isReturn then DPIDirection.Return else DPIDirection.Out
                  case value if value == CAPI.InOut()  => DPIDirection.InOut
                  case other => throw new IllegalArgumentException(s"unsupported DPI port direction: $other")
                summon[DPIArgumentApi].createDPIArgument(
                  Attribute(port.portName).stringAttrGetValue,
                  Type(port.portType),
                  direction
                )
              var symbol = s"__zaozi_dpi_export_$functionIndex"
              while !operationIsNull(symbolTable.lookup(symbol)) do symbol += "_"
              summon[DPIFuncApi].op(symbol, Some(name), arguments, function.getLocation)
                .operation.appendToBlock()(using exportModule.getBody)
        finally symbolTable.destroy()
        val json = new StringBuilder
        val (result, diagnostics) = withCapturedDiagnostics:
          exportModule.exportDPIInterface(json.append(_))
        if result.failed then
          throw new IllegalStateException(s"CIRCT DPI interface export failed: ${diagnostics.trim}")
        ujson.read(json.toString)
      finally copy.destroy()

    def toVerilog(
      using Arena
    ): String =
      given Context = module.getContext
      // Preserve the original IR for subsequent bytecode and DPI exports.
      val lowered   = summon[OperationApi].operationClone(module.getOperation)
      try
        val firtoolOptions = summon[FirtoolApi].firtoolOptionsCreateDefault
        try
          val verilog     = new StringBuilder
          val passManager = summon[PassManagerApi].passManagerCreate
          try
            passManager.hwToSV(firtoolOptions)
            passManager.exportVerilog(firtoolOptions, verilog.append(_))
            passManager.runOnOpOrThrow(lowered, "unit testbench SystemVerilog export")
          finally passManager.destroy()
          verilog.toString
        finally firtoolOptions.destroy
      finally lowered.destroy()
