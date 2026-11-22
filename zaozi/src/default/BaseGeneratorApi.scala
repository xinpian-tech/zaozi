// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_AttributeApi,
  given_FirrtlBundleFieldApi,
  given_FirrtlDirectionApi,
  given_TypeApi,
  FirrtlLayerConvention,
  FirrtlNameKind
}
import org.llvm.circt.scalalib.dialect.firrtl.operation.given
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  given_CircuitApi,
  given_InstanceApi,
  given_InstanceChoiceApi,
  given_LayerApi,
  given_OpenSubfieldApi,
  given_RefDefineApi,
  given_WireApi,
  Circuit,
  CircuitApi,
  InstanceApi,
  InstanceChoiceApi,
  LayerApi,
  OpenSubfieldApi,
  RefDefineApi,
  WireApi
}
import org.llvm.mlir.scalalib.capi.ir.{
  given_AttributeApi as mlirGivenAttributeApi,
  given_BlockApi,
  given_IdentifierApi,
  given_LocationApi,
  given_ModuleApi,
  given_NamedAttributeApi,
  given_OperationApi,
  given_RegionApi,
  given_TypeApi as mlirGivenTypeApi,
  given_ValueApi,
  Block,
  Context,
  LocationApi,
  Module as MlirModule,
  ModuleApi as MlirModuleApi,
  Operation,
  Value
}

import java.lang.foreign.Arena

import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_DialectApi, DialectApi as FirrtlDialect}
import org.llvm.circt.scalalib.capi.dialect.ltl.{given_DialectApi as given_Ltl, DialectApi as LtlDialect}
import org.llvm.circt.scalalib.capi.dialect.verif.{given_DialectApi as given_Verif, DialectApi as VerifDialect}
import org.llvm.mlir.scalalib.capi.ir.{given_ContextApi, ContextApi}

object BaseGeneratorHelper:
  private[default] def declareLayers(
    layers: Seq[LayerTree],
    parent: Block
  )(
    using Arena,
    Context
  ): Unit =
    layers.foreach: layer =>
      val declaration =
        summon[LayerApi].op(layer.name, summon[LocationApi].locationUnknownGet, FirrtlLayerConvention.Bind)
      parent.appendOwnedOperation(declaration.operation)
      declareLayers(layer.children, declaration.block)

  def createInstance[
    PARAM <: Parameter,
    L <: LayerInterface[PARAM],
    I <: HWInterface[PARAM],
    P <: DVInterface[PARAM, L]
  ](moduleName: String,
    ioTpe:      I,
    probeTpe:   P,
    layers:     L
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Instance[I, P] =
    val ioFields    = Seq.tabulate(ioTpe.toMlirType.getBundleNumFields.toInt)(ioTpe.toMlirType.getBundleFieldByIndex)
    val probeFields =
      Seq.tabulate(probeTpe.toMlirType.getBundleNumFields.toInt)(probeTpe.toMlirType.getBundleFieldByIndex)
    val instanceOp  = summon[InstanceApi].op(
      moduleName = moduleName,
      instanceName = valName,
      nameKind = FirrtlNameKind.Interesting,
      location = locate,
      interface = ioFields ++ probeFields,
      layers = layers.nameHierarchies
    )
    wrapInstance(instanceOp.operation, ioTpe, probeTpe)

  /** Instantiates the first case by default; `targets$<option>$<case>` selects a case. */
  private[zaozi] def instantiateChoice[L <: LayerInterface[?], I <: HWInterface[?], P <: DVInterface[?, ?]](
    option: String,
    cases:  Seq[(String, String, I, P, L)]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Instance[I, P] =
    require(cases.nonEmpty, "an instance choice needs at least one case")
    require(cases.forall(_._1.nonEmpty), "instance choice case names must not be empty")
    require(cases.map(_._1).distinct.size == cases.size, "instance choice case names must be distinct")
    val (_, _, io, probe, layers) = cases.head
    val moduleNames               = cases.map(_._2)
    val ioFields                  = Seq.tabulate(io.toMlirType.getBundleNumFields.toInt)(io.toMlirType.getBundleFieldByIndex)
    val probeFields               = Seq.tabulate(probe.toMlirType.getBundleNumFields.toInt)(probe.toMlirType.getBundleFieldByIndex)
    val instanceOp                = summon[InstanceChoiceApi].op(
      moduleNames = moduleNames.head +: moduleNames,
      option = option,
      cases = cases.map(_._1),
      instanceName = valName,
      nameKind = FirrtlNameKind.Interesting,
      location = locate,
      interface = ioFields ++ probeFields,
      layers = layers.nameHierarchies
    )
    wrapInstance(instanceOp.operation, io, probe)

  private def wrapInstance[I <: HWInterface[?], P <: DVInterface[?, ?]](
    instanceOp: Operation,
    ioTpe:      I,
    probeTpe:   P
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Instance[I, P] =
    val ioFields    = Seq.tabulate(ioTpe.toMlirType.getBundleNumFields.toInt)(ioTpe.toMlirType.getBundleFieldByIndex)
    val probeFields =
      Seq.tabulate(probeTpe.toMlirType.getBundleNumFields.toInt)(probeTpe.toMlirType.getBundleFieldByIndex)
    instanceOp.appendToBlock()
    val probeWire   = summon[WireApi].op(
      s"${valName}_probe",
      summon[LocationApi].locationUnknownGet,
      FirrtlNameKind.Droppable,
      probeTpe.toMlirType
    )
    probeWire.operation.appendToBlock()

    probeFields.zipWithIndex.foreach: (field, idx) =>
      val instanceIO = instanceOp.getResult(ioFields.length + idx)
      val wireProbe  = summon[OpenSubfieldApi].op(
        probeWire.result,
        idx,
        locate
      )
      wireProbe.operation.appendToBlock()
      val connect    = summon[RefDefineApi].op(wireProbe.result, instanceIO, locate)
      connect.operation.appendToBlock()

    new Instance[I, P]:
      val _ioTpe     = ioTpe
      val _probeTpe  = probeTpe
      val _operation = instanceOp
      val _io        = new Interface[I](ioTpe, IArray.tabulate(ioFields.length)(idx => instanceOp.getResult(idx)))
      val _probeWire = new Wire[P]:
        private[zaozi] val _tpe   = probeTpe
        private[zaozi] val _refer = probeWire.operation.getResult(0)

  def dumpMlirbc(moduleName: String)(createModule: (Arena, Context, Circuit) ?=> Unit): Unit =
    Elaborate.generate(moduleName)(file => writeMlirbc(moduleName, file)(createModule))

  private[default] def writeMlirbc(moduleName: String, file: os.Path)(createModule: (Arena, Context, Circuit) ?=> Unit)
    : Unit =
    given arena: Arena = Arena.ofConfined()
    try
      given context: Context = summon[ContextApi].contextCreateWithThreading(false)
      try
        summon[FirrtlDialect].loadDialect
        summon[LtlDialect].loadDialect
        summon[VerifDialect].loadDialect
        given module: MlirModule = summon[MlirModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
        try
          given Circuit = summon[CircuitApi].op(moduleName)
          summon[Circuit].appendToModule()
          createModule
          summon[Circuit].declareInstantiatedModules()
          ModuleBytecode.write(module, file)
        finally module.destroy()
      finally context.destroy()
    finally arena.close()

end BaseGeneratorHelper
