// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.tblib.default

import me.jiuyang.tblib.{Testbench, TestbenchGenerator, TestbenchGeneratorApi}
import me.jiuyang.zaozi.{DVInterface, HWApi, HWInterface, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{*, given}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{DialectApi as FIRRTLDialectApi, LinkCircuitsPassApi, given}
import org.llvm.circt.scalalib.capi.dialect.emit.{DialectApi as EmitDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.comb.{DialectApi as CombDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.ltl.{DialectApi as LTLDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.seq.{DialectApi as SeqDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.sim.{DialectApi as SimDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.sv.{DialectApi as SVDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.verif.{DialectApi as VerifDialectApi, given}
import org.llvm.circt.scalalib.capi.firtool.{FirtoolApi, given}
import org.llvm.circt.scalalib.dialect.hw.operation.{Module as HWModule, PortDirection, given}
import org.llvm.mlir.scalalib.capi.ir.{
  Block,
  Context,
  LocationApi,
  Module,
  ModuleApi,
  Operation,
  OperationApi,
  SymbolTableApi,
  given
}
import org.llvm.mlir.scalalib.capi.pass.{PassManagerApi, given}
import org.llvm.mlir.scalalib.capi.support.given_LogicalResultApi
import org.llvm.mlir.{MlirModule, MlirOperation}

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
      val period        = generator.clockPeriodNs(parameter)
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
        val dutModule = findHWModule(module, testbenchName)
        val hwPorts   = dutModule.ports
        val interface = generator.interface(parameter)
        interface.toMlirType
        val fields    = interface.elements
        require(
          hwPorts.size == fields.size && hwPorts
            .zip(fields)
            .forall: (port, field) =>
              port.name == field.name &&
                port.direction == (if field.isFlipped then PortDirection.Input else PortDirection.Output),
          "lowered HW ports do not match the testbench interface"
        )
        require(
          hwPorts.headOption.exists(port => port.name == "clock" && port.tpe.isClock),
          "first testbench port must be clock"
        )

        given Block = module.getBody
        summon[CombDialectApi].loadDialect
        val clockModule = ClockModule.create(period)
        summon[HWApi].module(generator.wrapperName(parameter), Seq.empty):
          val seqClock = summon[HWApi].instance("clockGenerator", clockModule, Seq.empty).head
          given testbench: Testbench[I] = new DefaultTestbench[I](seqClock, fields.tail, hwPorts.tail)
          generator.simulation(parameter)
          val outputs = summon[HWApi].instance("testbench", dutModule, testbench.inputValues)
          testbench.connectOutputs(outputs)
          summon[HWApi].output(Seq.empty)

        require(module.getOperation.verify, "invalid unit testbench wrapper")
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
      val json = new StringBuilder
      if module.exportDPIInterface(json.append(_)).failed then
        throw new IllegalStateException("CIRCT DPI interface export failed")
      ujson.read(json.toString)

    def toVerilog(
      using Arena
    ): String =
      given Context = module.getContext
      // HW-to-SV consumes Sim operations, which subsequent bytecode and DPI exports still need.
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
