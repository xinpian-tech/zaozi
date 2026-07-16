// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

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
import org.llvm.circt.scalalib.capi.dialect.sv.given_DialectApi as SvDialectApi
import org.llvm.circt.scalalib.dialect.firrtl.operation.given
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  given_CircuitApi,
  given_ModuleApi,
  Circuit,
  CircuitApi,
  ConnectApi,
  ExtModule as CirctExtModule,
  ExtModuleApi,
  InstanceApi,
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

import java.lang.foreign.Arena

given VerilogWrapperApi:
  extension [
    PARAM <: Parameter,
    L <: LayerInterface[PARAM],
    I <: HWInterface[PARAM],
    P <: DVInterface[PARAM, L],
    V <: VerilogParameter
  ](wrapper: VerilogWrapper[PARAM, L, I, P, V]
  )
    /** Declares `wrapper` as a FIRRTL `extmodule` (a black-boxed external module, generated verilog-side and given by
      * name only) with `wrapper.verilogParameter`'s fields as Verilog module parameters (`String`,
      * `BigInt`/`Int`/`Long`, `Double`, and `Boolean` values only).
      */
    def extmodule(
      parameter: PARAM
    )(
      using Arena,
      Context
    ): CirctExtModule =
      val io              = wrapper.interface(parameter)
      val probe           = wrapper.probe(parameter)
      val unknownLocation = summon[LocationApi].locationUnknownGet
      val ioNumFields     = io.toMlirType.getBundleNumFields.toInt
      val probeNumFields  = probe.toMlirType.getBundleNumFields.toInt
      val bfs             =
        Seq.tabulate(ioNumFields)(io.toMlirType.getBundleFieldByIndex) ++
          Seq.tabulate(probeNumFields)(probe.toMlirType.getBundleFieldByIndex)
      val verilogParams   = wrapper.verilogParameter(parameter)
      val paramsMap       = verilogParams.productElementNames
        .zip(verilogParams.productIterator)
        .collect:
          case (name, value) =>
            value match
              case str:  String  => (name, str)
              case int:  BigInt  => (name, int)
              case int:  Int     => (name, BigInt(int))
              case lng:  Long    => (name, BigInt(lng))
              case dbl:  Double  => (name, dbl)
              case bool: Boolean => (name, bool)
              case _ =>
                throw new IllegalArgumentException(
                  s"Invalid Verilog parameter type for '$name': ${value.getClass.getName}. Only String, BigInt, Int, Long, Double, and Boolean are supported."
                )
        .toMap
      val extmodule       = summon[ExtModuleApi].op(
        wrapper.moduleName(parameter),
        wrapper.verilogModuleName(parameter),
        unknownLocation,
        FirrtlConvention.Scalarized,
        bfs.map(i => (i, unknownLocation)), // TODO: record location for Bundle?
        wrapper.layers(parameter).nameHierarchies,
        paramsMap
      )
      extmodule

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
        wrapper.moduleName(parameter),
        wrapper.interface(parameter),
        wrapper.probe(parameter),
        wrapper.layers(parameter)
      )

    def dumpMlirbc(
      parameter: PARAM
    )(
      using Arena,
      Context
    ): Unit =
      BaseGeneratorHelper.dumpMlirbc(
        wrapper.moduleName(parameter),
        wrapper.elaboratedModules,
        parameter,
        (arena, context, circuit) =>
          given Arena   = arena
          given Context = context
          given Circuit = circuit
          wrapper.extmodule(parameter).appendToCircuit()
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
      wrapper.dumpMlirbc(parameter)
      wrapper.instance(parameter)

end given
