// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import scala.util.Try

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.magic.validateCircuit
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.capi.dialect.emit.given_DialectApi as EmitDialectApi
import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_DialectApi,
  given_FirrtlBundleFieldApi,
  given_FirrtlDirectionApi,
  given_TypeApi,
  DialectApi as FirrtlDialectApi,
  FirrtlConvention,
  FirrtlNameKind
}
import org.llvm.circt.scalalib.capi.dialect.ltl.{given_DialectApi as given_LTLDialectApi, DialectApi as LTLDialectApi}
import org.llvm.circt.scalalib.capi.dialect.sv.given_DialectApi as SvDialectApi
import org.llvm.circt.scalalib.capi.dialect.verif.{
  given_DialectApi as given_VerifDialectApi,
  DialectApi as VerifDialectApi
}
import org.llvm.circt.scalalib.dialect.firrtl.operation.given
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  given_CircuitApi,
  given_ModuleApi,
  Circuit,
  CircuitApi,
  ConnectApi,
  InstanceApi,
  Module as CirctModule,
  ModuleApi,
  OpenSubfieldApi,
  RefDefineApi,
  SubfieldApi,
  WireApi
}
import org.llvm.mlir.scalalib.capi.ir.{
  given_AttributeApi,
  given_BlockApi,
  given_ContextApi,
  given_IdentifierApi,
  given_LocationApi,
  given_ModuleApi,
  given_NamedAttributeApi,
  given_OperationApi,
  given_RegionApi,
  given_TypeApi,
  given_ValueApi,
  Block,
  Context,
  ContextApi,
  LocationApi,
  Module as MlirModule,
  ModuleApi as MlirModuleApi,
  NamedAttributeApi,
  Operation,
  OperationApi,
  Type
}
import org.llvm.mlir.scalalib.capi.pass.{given_PassManagerApi, PassManager}

import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.nio.file.StandardOpenOption.*

export me.jiuyang.zaozi.magic.macros.generator

/** Implements `GeneratorApi`: builds the `firrtl.module` for a `Generator` from its `interface`/`probe`/`layers`
  * (wiring the `io`/`probe` wires the architecture body reads and writes to the module's actual ports), and the
  * `instance`/`instantiate`/`dumpMlirbc`/`mainImpl` machinery around it. See `me.jiuyang.zaozi.GeneratorApi` in
  * `me.jiuyang.zaozi.Api`.
  */
given GeneratorApi:
  extension [PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    generator: Generator[PARAM, L, I, P]
  )
    def module(
      parameter: PARAM
    )(
      using Arena,
      Context
    ): CirctModule =
      val io                = generator.interface(parameter)
      val probe             = generator.probe(parameter)
      val unknownLocation   = summon[LocationApi].locationUnknownGet
      val ioNumFields       = io.toMlirType.getBundleNumFields.toInt
      val probeNumFields    = probe.toMlirType.getBundleNumFields.toInt
      val bfs               =
        Seq.tabulate(ioNumFields)(io.toMlirType.getBundleFieldByIndex) ++
          Seq.tabulate(probeNumFields)(probe.toMlirType.getBundleFieldByIndex)
      val module            = summon[ModuleApi].op(
        generator.moduleName(parameter),
        unknownLocation,
        FirrtlConvention.Scalarized,
        bfs.map(i => (i, unknownLocation)), // TODO: record location for Bundle?
        generator.layers(parameter).nameHierarchies
      )
      given Block           = module.block
      val ioWire            = summon[WireApi].op(
        "io",
        unknownLocation,
        FirrtlNameKind.Droppable,
        io.toMlirType
      )
      ioWire.operation.appendToBlock()
      val probeWire         = summon[WireApi].op(
        "probe",
        unknownLocation,
        FirrtlNameKind.Droppable,
        probe.toMlirType
      )
      probeWire.operation.appendToBlock()
      Seq
        .tabulate(ioNumFields): ioIdx =>
          (bfs(ioIdx), ioIdx)
        .foreach:
          case (bf, idx) =>
            val subRefToIOWire = summon[SubfieldApi].op(
              ioWire.result,
              idx,
              unknownLocation
            )
            subRefToIOWire.operation.appendToBlock()
            (
              if (bf.getIsFlip)
                summon[ConnectApi].op(module.getIO(idx), subRefToIOWire.result, unknownLocation)
              else
                summon[ConnectApi].op(subRefToIOWire.result, module.getIO(idx), unknownLocation)
            ).operation.appendToBlock()
      Seq
        .tabulate(probeNumFields): probeIdx =>
          (bfs(ioNumFields + probeIdx), probeIdx)
        .foreach:
          case (bf, idx) =>
            val subRefToProbeWire = summon[OpenSubfieldApi].op(
              probeWire.result,
              idx,
              unknownLocation
            )
            subRefToProbeWire.operation.appendToBlock()
            summon[RefDefineApi]
              .op(module.getIO(ioNumFields + idx), subRefToProbeWire.result, unknownLocation)
              .operation
              .appendToBlock()
      given Interface[I]    =
        new Interface[I]:
          val _tpe:       I         = io
          val _operation: Operation = ioWire.operation
      given Interface[P]    =
        new Interface[P]:
          val _tpe:       P         = probe
          val _operation: Operation = probeWire.operation
      given InstanceContext = new InstanceContext
      given L               = generator.layers(parameter)
      generator.architecture(parameter)
      module

    def instance(
      parameter: PARAM
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Instance[I, P] =
      BaseGeneratorHelper.createInstance(
        generator.moduleName(parameter),
        generator.interface(parameter),
        generator.probe(parameter),
        generator.layers(parameter)
      )

    def dumpMlirbc(
      parameter: PARAM
    )(
      using Arena,
      Context
    ): Unit =
      BaseGeneratorHelper.dumpMlirbc(
        generator.moduleName(parameter),
        generator.elaboratedModules,
        parameter,
        (arena, context, circuit) =>
          given Arena   = arena
          given Context = context
          given Circuit = circuit
          generator.module(parameter).appendToCircuit()
      )

    def instantiate(
      parameter: PARAM
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Instance[I, P] =
      generator.dumpMlirbc(parameter)
      generator.instance(parameter)

    private def configImpl(
      parameter:  PARAM,
      configFile: os.Path
    )(
      using upickle.default.Writer[PARAM]
    ) = os.write.over(configFile, upickle.default.write(parameter))

    private def designImpl(
      configFile: os.Path
    )(
      using upickle.default.Reader[PARAM]
    ) =
      val arena = Arena.ofConfined()
      try
        given Arena   = arena
        given Context = summon[ContextApi].contextCreate
        summon[FirrtlDialectApi].loadDialect
        summon[LTLDialectApi].loadDialect
        summon[VerifDialectApi].loadDialect

        generator.dumpMlirbc(upickle.default.read(os.read(configFile)))

        summon[Context].destroy()
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

end given
