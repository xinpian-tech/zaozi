// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.default

import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.sv.*
import me.jiuyang.zaozi.default.{locate, valName}
import org.llvm.circt.scalalib.capi.dialect.hw.{
  given_DialectApi as given_HWDialect,
  given_HWModulePortApi,
  given_TypeApi as HWType,
  DialectApi as HWDialect
}
import org.llvm.circt.scalalib.capi.dialect.sv.{given_DialectApi as given_SVDialect, DialectApi as SVDialect}
import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_DialectApi as given_FIRRTLDialect,
  given_FirrtlBundleFieldApi,
  given_FirrtlDirectionApi,
  given_TypeApi as FIRRTLType,
  DialectApi as FIRRTLDialect,
  FirrtlNameKind
}
import org.llvm.circt.scalalib.capi.exportverilog.given
import org.llvm.circt.scalalib.dialect.hw.operation.{ModuleApi as HWModuleApi, OutputApi, given}
import org.llvm.circt.scalalib.dialect.hw.tpe.{ModulePort, PortDirection}
import org.llvm.circt.scalalib.dialect.sv.operation.{ReadInOutApi, RegApi, given}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{InstanceApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Module, ModuleApi, *, given}
import org.llvm.mlir.scalalib.capi.support.given
import java.lang.foreign.Arena

export me.jiuyang.zaozi.magic.macros.generator

given SVGeneratorApi with
  extension [G <: SVGenerator & Singleton](generator: G)
    def elaborate(
    )(
      using Arena,
      Context
    ): Module =
      summon[HWDialect].loadDialect
      summon[SVDialect].loadDialect
      summon[FIRRTLDialect].loadDialect
      val loc                    = summon[LocationApi].locationUnknownGet
      val module                 = summon[ModuleApi].moduleCreateEmpty(loc)
      given SVElaborationContext = new SVElaborationContext(module)
      summon[SVElaborationContext].modules += generator.moduleName
      val declarations: generator.Parameters.type = generator.verilogParameters
      given generator.SVParameters = generator.symbolicParameters
      given TypeScopeContext       = new TypeScopeContext(generator.moduleName + "_types")
      val io: generator.IO.type = generator.svInterface
      io._ports.foreach(p => declareInterface(p.dataType))
      val ports          = io._ports.toSeq.map(p => ModulePort(p.name, p.dataType.toMlirType, p.direction))
      val externalSource = generator.source
      externalSource.foreach: source =>
        val attrs      = declarations._parameters.toSeq.map(_.declarationImpl)
        val sourceName = generator.moduleName + "_source"
        val file       = org.llvm.circt.scalalib.capi.dialect.hw.given_AttributeApi.outputFileGetFromFileName(
          source.fileName.stringAttrGet,
          false,
          false
        )
        summon[org.llvm.circt.scalalib.dialect.sv.operation.SVVerbatimSourceApi]
          .op(
            sourceName,
            content = source.content,
            outputFile = file,
            parameters = attrs,
            verilogName = generator.moduleName,
            location = loc
          )
          .operation
          .appendToBlock()(
            using module.getBody
          )
        import org.llvm.circt.scalalib.dialect.hw.tpe.given
        summon[org.llvm.circt.scalalib.dialect.sv.operation.SVVerbatimModuleApi]
          .op(
            generator.moduleName,
            moduleType = summon[org.llvm.circt.scalalib.dialect.hw.tpe.TypeApi].getModule(ports),
            perPortAttrs = Some(ports.map(_ => Map.empty[String, Attribute].directoryAttrGet)),
            portLocs = Some(ports.map(_ => loc)),
            parameters = attrs,
            source = sourceName,
            location = loc
          )
          .operation
          .appendToBlock()(
            using module.getBody
          )
      if externalSource.nonEmpty then
        io._ports.foreach(p => summon[TypeScopeContext].use(p.dataType))
        emitTypeDeclarations(summon[TypeScopeContext], module.getBody)
        require(module.getOperation.verify, s"Invalid SV source module ${generator.moduleName}")
        return module
      val hw = summon[HWModuleApi].op(
        generator.moduleName,
        ports,
        declarations._parameters.toSeq.map(_.declarationImpl),
        loc
      )
      hw.operation.appendToBlock()(
        using module.getBody
      )
      given Block             = hw.block
      var inputIndex          = 0
      val values              = io._ports.map: port =>
        val value = if port.direction == PortDirection.Output then
          val variable = summon[RegApi].op(port.dataType.toMlirType, name = port.name + "_out", location = loc)
          variable.operation.appendToBlock()
          variable.result
        else
          val input = hw.block.getArgument(inputIndex)
          inputIndex += 1
          input
        port.name -> value
      given generator.SVPorts = new Interface(io, values.toMap)
      given InstanceContext   = new InstanceContext
      generator.architecture
      generator.symbolicTypes.declarations.foreach(summon[TypeScopeContext].use)
      io._ports.foreach(p => summon[TypeScopeContext].use(p.dataType))
      emitTypeDeclarations(summon[TypeScopeContext], module.getBody)
      val outputs             = io._ports
        .filter(_.direction == PortDirection.Output)
        .map: port =>
          val read = summon[ReadInOutApi].op(values.toMap.apply(port.name), loc)
          read.operation.appendToBlock()
          read.result
      summon[OutputApi].op(outputs.toSeq, loc).operation.appendToBlock()
      require(module.getOperation.verify, s"Invalid SV module ${generator.moduleName}")
      module

    private def boundParameters(bindings: generator.SVParameters => Seq[ParameterBinding]): generator.SVParameters =
      val declarations: generator.Parameters.type = generator.verilogParameters
      val overrides = bindings(ParameterInterface(declarations))
      require(overrides.map(_.name).distinct.size == overrides.size, "Duplicate Verilog parameter binding")
      require(
        overrides.forall(b => declarations._parameters.exists(_.name == b.name)),
        "Unknown Verilog parameter binding"
      )
      new ParameterInterface(
        declarations,
        declarations._parameters.map(p => p.name -> ConstExpr(p.default)).toMap ++ overrides.map(b => b.name -> b.value)
      )

    private def concreteBindings(
      parameters: generator.SVParameters
    )(
      using Arena,
      Context,
      MacroEnvironment
    ): generator.FIRRTLBindings =
      val environment = summon[MacroEnvironment]
      val values      = parameters.getType._parameters.map: field =>
        val value = parameters
          .field(field.name)
          .toAttribute(
            using summon[Arena],
            summon[Context],
            new SVTypeImpl(Map.empty, Some(environment))
          )
        require(value.isInteger, s"FIRRTL requires a concrete binding for ${field.name}")
        field.name -> value.integerAttrGetValueInt.toInt
      ResolvedBindings(
        FIRRTLParameters[generator.Parameters.type](values.toMap),
        new FIRRTLMacros[generator.Macros.type](generator.Macros, environment)
      )

    private def macroAttributes(
      bindings: generator.FIRRTLBindings
    )(
      using Arena,
      Context
    ): Attribute =
      bindings.macros.environment.definitions.toSeq
        .sortBy(_._1)
        .map: (name, definition) =>
          val fields = definition match
            case MacroDefinition.Undefined      => Map("state" -> "undefined".stringAttrGet)
            case MacroDefinition.Defined        => Map("state" -> "defined".stringAttrGet)
            case MacroDefinition.Integer(value) =>
              Map("state" -> "integer".stringAttrGet, "value" -> value.toLong.integerAttrGet(32.integerTypeGet))
          (fields + ("name" -> name.stringAttrGet)).directoryAttrGet
        .arrayAttrGet

    private def facadeName(bindings: generator.FIRRTLBindings): String =
      val parameters = generator.moduleName + "_firrtl_" + generator.verilogParameters._parameters
        .map(p => bindings.parameters.field(p.name).toHexString)
        .mkString("_")
      val macros     = bindings.macros.environment.definitions.toSeq.sortBy(_._1)
      if macros.isEmpty then parameters
      else
        val canonical = macros.map((name, value) => s"${name.length}:$name=$value").mkString(";")
        val digest    = java.security.MessageDigest
          .getInstance("SHA-256")
          .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        parameters + "_macros_" + java.util.HexFormat.of().formatHex(digest)

    def verilogString(): String =
      val arena = Arena.ofConfined()
      try
        given Arena   = arena
        given Context = summon[ContextApi].contextCreate
        try
          val module = generator.elaborate()
          try
            val out = StringBuilder()
            require(module.exportVerilog(out ++= _).succeeded, "SV export failed")
            out.toString
          finally module.getOperation.destroy()
        finally summon[Context].destroy()
      finally arena.close()

    def instantiateImpl(
      bindings: generator.SVParameters => Seq[ParameterBinding] = (_: generator.SVParameters) => Seq.empty
    )(
      using MacroEnvironment,
      Arena,
      Context,
      Block,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): me.jiuyang.zaozi.sv.reftpe.FIRRTLInstance[generator.FIRRTLIO] =
      given me.jiuyang.zaozi.TypeImpl = me.jiuyang.zaozi.default.given_TypeImpl
      val parameters                  = concreteBindings(boundParameters(bindings))
      val io                          = generator.interface(parameters)
      val tpe                         = io.toMlirType
      val fields                      = Seq.tabulate(FIRRTLType.getBundleNumFields(tpe).toInt)(FIRRTLType.getBundleFieldByIndex(tpe)(_))
      val instance                    = summon[InstanceApi].op(
        facadeName(parameters),
        valName,
        FirrtlNameKind.Interesting,
        locate,
        fields,
        Seq.empty
      )
      // validateCircuit materializes this import without elaborating its implementation.
      instance.operation.setAttributeByName("zaozi.sv.module", generator.moduleName.stringAttrGet)
      instance.operation.setAttributeByName(
        "zaozi.sv.parameters",
        generator.verilogParameters._parameters.toSeq
          .map(field =>
            org.llvm.circt.scalalib.capi.dialect.firrtl.given_AttributeApi.paramDeclAttrGet(
              field.name,
              field.width.integerTypeGet,
              parameters.parameters.field(field.name).toLong.integerAttrGet(field.width.integerTypeGet)
            )
          )
          .arrayAttrGet
      )
      instance.operation.setAttributeByName("zaozi.sv.macros", macroAttributes(parameters))
      instance.operation.appendToBlock()
      new me.jiuyang.zaozi.sv.reftpe.FIRRTLInstance(
        new me.jiuyang.zaozi.reftpe.Interface(io, IArray.tabulate(fields.size)(i => instance.operation.getResult(i))),
        instance.operation
      )

    def instantiateSVImpl(
      bindings:    generator.SVParameters => Seq[ParameterBinding] = (_: generator.SVParameters) => Seq.empty,
      connections: Seq[PortBinding] = Seq.empty
    )(
      using Arena,
      Context,
      Block,
      SVElaborationContext,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): me.jiuyang.zaozi.sv.reftpe.Instance[generator.IO.type, generator.Parameters.type] =
      val parameters   = boundParameters(bindings)
      val declarations = parameters.getType
      val io:                                                       generator.IO.type = generator.svInterface
      // CIRCT's instance parameter evaluator accepts macro widths behind a type alias,
      // but aborts on a direct ParamVerbatimAttr in a scalar/array port width.
      def hasMacro(expr: ConstExpr):                                Boolean           = expr match
        case _:   ConstExpr.MacroValue => true
        case ref: ParameterRef         => ref.binding.exists(hasMacro)
        case ConstExpr.Expression(_, operands) => operands.exists(hasMacro)
        case _                                 => false
      def directMacroWidth(tpe: me.jiuyang.zaozi.sv.valuetpe.Data): Boolean           =
        import me.jiuyang.zaozi.sv.valuetpe.*
        tpe match
          case bits:  Bits             =>
            def boundMacro(expr: ConstExpr): Boolean = expr match
              case ref: ParameterRef => parameters.bindings.get(ref.field.name).fold(hasMacro(ref))(hasMacro)
              case ConstExpr.Expression(_, operands) => operands.exists(boundMacro)
              case other                             => hasMacro(other)
            boundMacro(bits._width)
          case array: PackedArray[?]   => directMacroWidth(array._element)
          case array: UnpackedArray[?] => directMacroWidth(array._element)
          case _ => false
      require(
        !io._ports.exists(p => directMacroWidth(p.dataType)),
        "CIRCT requires a TypeDef for macro-sized scalar/array instance ports"
      )
      val root = summon[SVElaborationContext]
      io._ports.foreach(p => declareInterface(p.dataType))
      if root.modules.add(generator.moduleName) then
        summon[org.llvm.circt.scalalib.dialect.hw.operation.ModuleExternApi]
          .op(
            generator.moduleName,
            io._ports.toSeq.map(p => ModulePort(p.name, p.dataType.toMlirType, p.direction)),
            declarations._parameters.toSeq.map(_.declarationImpl),
            locate
          )
          .operation
          .appendToBlock()(
            using root.module.getBody
          )
        val scope = new TypeScopeContext(generator.moduleName + "_types")
        io._ports.foreach(p => scope.use(p.dataType))
        emitTypeDeclarations(scope, root.module.getBody)
      val name = valName
      val inputs = io._ports
        .filter(_.direction != PortDirection.Output)
        .map: port =>
          connections.find(_.name == port.name) match
            case Some(binding) =>
              val value =
                if port.direction == PortDirection.InOut then
                  binding.value match
                    case ref: me.jiuyang.zaozi.sv.reftpe.Ref[?] => ref._refer
                    case _ => throw new IllegalArgumentException(s"Inout port ${port.name} needs a reference")
                else binding.value.refer
              (port.name, value, value)
            case None          =>
              require(
                !port.dataType.isInstanceOf[VerilogInterface] && !port.dataType.isInstanceOf[Modport[?]],
                s"Interface port ${port.name} needs an explicit connection"
              )
              val wire   = org.llvm.circt.scalalib.dialect.sv.operation.given_WireApi
                .op(
                  port.dataType.toMlirType(
                    using summon[Arena],
                    summon[Context],
                    new SVTypeImpl(parameters.bindings)
                  ),
                  name = name + "_" + port.name,
                  location = locate
                )
              wire._operation.appendToBlock()
              val handle = wire._operation.getResult(0)
              val value  =
                if port.direction == PortDirection.InOut then handle
                else
                  val read = summon[ReadInOutApi].op(handle, locate)
                  read.operation.appendToBlock()
                  read.result
              (port.name, handle, value)

      val outputs = io._ports.filter(_.direction == PortDirection.Output)
      val attrs   = declarations._parameters.toSeq.map(p =>
        org.llvm.circt.scalalib.capi.dialect.hw.given_AttributeApi
          .paramDeclAttrGet(p.name, p.width.integerTypeGet, parameters.field(p.name).toAttribute)
      )
      val op      = org.llvm.circt.scalalib.dialect.hw.operation.given_InstanceApi.op(
        name,
        generator.moduleName,
        inputs.map(_._3).toSeq,
        inputs.map(_._1).toSeq,
        outputs
          .map(
            _.dataType.toMlirType(
              using summon[Arena],
              summon[Context],
              new SVTypeImpl(parameters.bindings)
            )
          )
          .toSeq,
        outputs.map(_.name).toSeq,
        attrs,
        locate
      )
      op._operation.appendToBlock()
      val values  = inputs.map((name, handle, _) => name -> handle).toMap ++ outputs.zipWithIndex.map((p, i) =>
        p.name -> op._operation.getResult(i)
      )
      new me.jiuyang.zaozi.sv.reftpe.Instance(new Interface(io, values, parameters.bindings), parameters, op._operation)

    def mainImpl(args: Array[String]): Unit = args.toList match
      case "verilog" :: path :: Nil =>
        os.write.over(os.Path(path, os.pwd), generator.verilogString())
      case "design" :: path :: Nil  =>
        val arena = Arena.ofConfined()
        try
          given Arena   = arena
          given Context = summon[ContextApi].contextCreate
          try
            val module = generator.elaborate()
            val stream = os.write.outputStream(os.Path(path, os.pwd))
            try module.getOperation.writeBytecode(bytes => stream.write(bytes))
            finally
              stream.close()
              module.getOperation.destroy()
          finally summon[Context].destroy()
        finally arena.close()
      case _                        => throw new IllegalArgumentException("Expected verilog <output.sv> or design <output.mlirbc>")
