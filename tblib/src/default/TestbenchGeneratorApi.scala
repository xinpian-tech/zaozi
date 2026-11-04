// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.tblib.default

import me.jiuyang.tblib.{TestbenchContext, TestbenchGenerator, TestbenchGeneratorApi, TestbenchIO, TestbenchPort}
import me.jiuyang.zaozi.{DVInterface, DpiArg, DpiCallResult, DpiFunction, Generator, HWInterface, LayerInterface, Parameter, SVApi, SimApi}
import me.jiuyang.zaozi.valuetpe.Data
import me.jiuyang.zaozi.default.{*, given}

import org.llvm.circt.CAPI
import org.llvm.circt.scalalib.capi.dialect.firrtl.{DialectApi as FIRRTLDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.emit.{DialectApi as EmitDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialectApi, HWModulePort, TypeApi as HWTypeApi, given}
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
  ContextApi,
  Module,
  ModuleApi,
  Operation,
  OperationApi,
  SymbolTableApi,
  Type,
  Value,
  given
}
import org.llvm.mlir.scalalib.capi.pass.{PassManagerApi, given}
import org.llvm.mlir.scalalib.capi.support.given_LogicalResultApi
import org.llvm.mlir.{MlirAttribute, MlirOperation}

import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import scala.util.Try
import scala.util.control.NonFatal

given TestbenchGeneratorApi with
  extension [T <: Data](port: TestbenchPort[T])
    def connect(value: Value)(using Arena, Context, Block): Unit =
      require(
        summon[Block].getParentOperation.getName.str == "hw.module",
        "DUT inputs must be connected at module scope"
      )
      require(!port.connected, s"DUT input is already connected: ${port.name}")
      require(value.getType.equal(port.argument.getType), s"DUT input type mismatch: ${port.name}")
      port.argument.replaceAllUsesOfWith(value)
      port.connected = true

  extension [I <: HWInterface[?]](testbench: TestbenchContext[I])
    def initial(
      body: Block ?=> Unit
    )(
      using Arena,
      Context,
      Block
    ): Unit =
      require(summon[Block].getParentOperation.getName.str == "hw.module", "initial requires the testbench module scope")
      summon[SVApi].initial(body)

    private def inProcedure(
      using Arena,
      Block
    ): Boolean =
      Iterator
        .iterate(summon[Block].getParentOperation)(_.getParentOperation)
        .takeWhile(operation => MlirOperation.ptr(operation.segment).address != 0)
        .exists(operation => Set("sv.always", "sv.initial").contains(operation.getName.str))

    def onClock(
      clock:   Value,
      enabled: Option[Value]
    )(body:    Block ?=> Unit
    )(
      using Arena,
      Context,
      Block
    ): Unit =
      require(summon[Block].getParentOperation.getName.str == "hw.module", "onClock requires the testbench module scope")
      summon[SVApi].onClock(clock, enabled)(body)

    def dpiFunction(
      symbol:    String,
      cName:     Option[String],
      arguments: Seq[DpiArg]
    )(
      using Arena,
      Context,
      Block
    ): DpiFunction =
      val module = Iterator
        .iterate(summon[Block].getParentOperation)(_.getParentOperation)
        .takeWhile(operation => MlirOperation.ptr(operation.segment).address != 0)
        .find(_.getName.str == "builtin.module")
        .getOrElse(throw new IllegalArgumentException("DPI declaration requires an enclosing builtin.module"))
      locally:
        given Block = module.getFirstRegion.getFirstBlock
        summon[SVApi].dpiFunction(symbol, cName, arguments)

    def dpiCall(
      function: DpiFunction,
      inputs:   Seq[Value],
      enabled:  Option[Value]
    )(
      using Arena,
      Context,
      Block
    ): DpiCallResult =
      require(
        summon[Block].getParentOperation.getName.str == "hw.module",
        "dpiCall creates an independent process; use dpiCallProcedural inside onClock"
      )
      val sv = summon[SVApi]
      val outputs = function.arguments.filter(arg =>
        arg.direction == DPIDirection.Out || arg.direction == DPIDirection.Return
      )
      val registers = outputs.map: arg =>
        val tpe = if arg.signed then arg.width.integerTypeSignedGet else arg.width.integerTypeGet
        arg.name -> sv.reg(tpe, s"${function.symbol}_${arg.name}")
      onClock(testbench.fallingClock, enabled):
        val values = sv.dpiCallProcedural(function, inputs)
        registers.foreach((name, register) => sv.nonBlockingAssign(register, values(name)))
      DpiCallResult(registers.map((name, register) => name -> register.readInOut).toMap)

    def finish(
      condition: Value,
      success:   Boolean
    )(
      using Arena,
      Context,
      Block
    ): Unit =
      if inProcedure then
        summon[SVApi].ifElse(condition) {
          summon[SimApi].terminate(success)
        }()
      else summon[SimApi].clockedTerminate(testbench.clock, condition, success)

  extension [PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    dut: Generator[PARAM, L, I, P]
  )
    def io(using testbench: TestbenchContext[I]): TestbenchIO[I] = testbench.io

  private def operationIsNull(operation: Operation): Boolean = MlirOperation.ptr(operation.segment).address == 0

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
    private def configImpl(
      parameter:  PARAM,
      configFile: os.Path
    )(
      using upickle.default.Writer[PARAM]
    ): Unit = os.write.over(configFile, upickle.default.write(parameter))

    private def designImpl(
      configFile: os.Path
    )(
      using upickle.default.Reader[PARAM]
    ): Unit =
      val parameter = upickle.default.read[PARAM](os.read(configFile))
      val directory = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", ""), os.pwd)
      os.makeDir.all(directory)
      val arena     = Arena.ofConfined()
      try
        given Arena   = arena
        given Context = summon[ContextApi].contextCreate
        try
          summon[CombDialectApi].loadDialect
          summon[FIRRTLDialectApi].loadDialect
          summon[EmitDialectApi].loadDialect
          summon[HWDialectApi].loadDialect
          summon[LTLDialectApi].loadDialect
          summon[SeqDialectApi].loadDialect
          summon[SimDialectApi].loadDialect
          summon[SVDialectApi].loadDialect
          summon[VerifDialectApi].loadDialect
          val name   = generator.moduleName(parameter)
          val module = lowerTestbench(parameter)
          try
            val bytecode = module.toMlirBytecode
            val dpi      = module.toDpiJson
            val verilog  = module.toVerilog
            os.write.over(directory / s"$name.mlirbc", bytecode)
            os.write.over(directory / s"$name.json", ujson.write(dpi, indent = 2))
            os.write.over(directory / s"$name.sv", verilog)
          finally module.destroy()
        finally summon[Context].destroy()
      finally arena.close()

    def mainImpl(
      args: Array[String]
    )(
      using upickle.default.ReadWriter[PARAM]
    ): Unit =
      args.toList match
        case subcmd :: configPath :: tail if Try(os.Path(configPath, os.pwd)).isSuccess =>
          val configFile = os.Path(configPath, os.pwd)
          subcmd match
            case "config" => configImpl(generator.parseParameter(tail), configFile)
            case "design" => designImpl(configFile)
        case _                                                                          =>
          println("Need to specify a sub command and provide a config path: config/design <path>")
          sys.exit(1)

    private def lowerTestbench(
      parameter: PARAM
    )(
      using Arena,
      Context
    ): Module =
      val module = Elaborate(generator, parameter)
      try
        // Lower modules from firrtl to hw
        val testbenchName = generator.moduleName(parameter)
        lowerFIRRTLToHW(module, testbenchName)
        require(module.getOperation.verify, "invalid lowered unit under test")

        // Check ports and args mismatch
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
        given Block         = testbenchModule.block
        val block           = summon[Block]

        // Find the clock instance created by architecture.
        val clockInstance   = Iterator
          .iterate(block.getFirstOperation)(_.getNextInBlock)
          .takeWhile(operation => !operationIsNull(operation))
          .find(operation =>
            operation.getName.str == "hw.instance" &&
              operation.getInherentAttributeByName("instanceName").stringAttrGetValue == "clock"
          )
          .getOrElse(throw new IllegalStateException("testbench clock instance is missing"))
        val arguments       = hwPorts.indices.map(index => block.getArgument(index.toLong))

        // Remove hw.output first and append it later
        val terminator = block.getTerminator
        terminator.removeFromParent()

        // Add Simulation logic
        try
          val clock = clockInstance.getResult(0)
          val fallingClock = clock.clockInv
          val inputPorts = hwPorts.zip(arguments).map { (port, argument) =>
            port.name -> new TestbenchPort[Data](port.name, argument)
          }.toMap
          given testbench: TestbenchContext[I] =
            new TestbenchContext[I](clock, fallingClock, inputPorts)
          generator.simulation(parameter)
          hwPorts.foreach(port => require(inputPorts(port.name).connected, s"DUT input is not connected: ${port.name}"))
          hwPorts.indices.reverse.foreach(block.eraseArgument)
          testbenchModule.operation.setInherentAttributeByName(
            "module_type",
            summon[HWTypeApi].moduleTypeGet(0, Seq.empty[HWModulePort]).typeAttrGet
          )
          testbenchModule.operation.setInherentAttributeByName("per_port_attrs", Seq.empty[Attribute].arrayAttrGet)
          testbenchModule.operation.setInherentAttributeByName("result_locs", Seq.empty[Attribute].arrayAttrGet)
        finally terminator.appendToBlock()

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
      val copy      = summon[OperationApi].operationClone(module.getOperation)
      try
        val exportModule          = summon[ModuleApi].moduleFromOperation(copy)
        val symbolTable           = summon[SymbolTableApi].symbolTableCreate(copy)
        try
          // The current native exporter accepts Sim declarations. Adapt SV imports in this copy only.
          Iterator
            .iterate(module.getBody.getFirstOperation)(_.getNextInBlock)
            .takeWhile(operation => !operationIsNull(operation))
            .filter(_.getName.str == "sv.func.dpi.import")
            .zipWithIndex
            .foreach: (dpiImport, functionIndex) =>
              val callee        = dpiImport.getInherentAttributeByName("callee").flatSymbolRefAttrGetValue
              val function      = symbolTable.lookup(callee)
              require(
                !operationIsNull(function) && function.getName.str == "sv.func",
                s"DPI function not found: $callee"
              )
              val linkageName   = dpiImport.getInherentAttributeByName("linkage_name")
              val verilogName   = function.getInherentAttributeByName("verilogName")
              val name          =
                if MlirAttribute.ptr(linkageName.segment).address != 0 then linkageName.stringAttrGetValue
                else if MlirAttribute.ptr(verilogName.segment).address != 0 then verilogName.stringAttrGetValue
                else callee
              val tpe           = function.getInherentAttributeByName("module_type").typeAttrGetValue
              val argumentAttrs = function.getInherentAttributeByName("per_argument_attrs")
              val portCount     = tpe.moduleTypeGetNumInputs() + tpe.moduleTypeGetNumOutputs()
              val arguments     = (0 until portCount).map: index =>
                val port      = tpe.moduleTypeGetPort(index)
                val isReturn  = index == portCount - 1 && MlirAttribute.ptr(argumentAttrs.segment).address != 0 &&
                  MlirAttribute
                    .ptr(
                      argumentAttrs
                        .arrayAttrGetElement(index)
                        .dictionaryAttrGetElementByName("sv.func.explicitly_returned")
                        .segment
                    )
                    .address != 0
                val direction = port.portDirection match
                  case value if value == CAPI.Input()  => DPIDirection.In
                  case value if value == CAPI.Output() => if isReturn then DPIDirection.Return else DPIDirection.Out
                  case value if value == CAPI.InOut()  => DPIDirection.InOut
                  case other                           => throw new IllegalArgumentException(s"unsupported DPI port direction: $other")
                summon[DPIArgumentApi].createDPIArgument(
                  Attribute(port.portName).stringAttrGetValue,
                  Type(port.portType),
                  direction
                )
              var symbol        = s"__zaozi_dpi_export_$functionIndex"
              while !operationIsNull(symbolTable.lookup(symbol)) do symbol += "_"
              summon[DPIFuncApi]
                .op(symbol, Some(name), arguments, function.getLocation)
                .operation
                .appendToBlock()(
                  using exportModule.getBody
                )
        finally symbolTable.destroy()
        val json                  = new StringBuilder
        val (result, diagnostics) = withCapturedDiagnostics:
          exportModule.exportDPIInterface(json.append(_))
        if result.failed then throw new IllegalStateException(s"CIRCT DPI interface export failed: ${diagnostics.trim}")
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
