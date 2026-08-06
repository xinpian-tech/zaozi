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
  FirrtlNameKind
}
import org.llvm.circt.scalalib.dialect.firrtl.operation.given
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  given_CircuitApi,
  given_InstanceApi,
  given_OpenSubfieldApi,
  given_RefDefineApi,
  given_WireApi,
  Circuit,
  CircuitApi,
  InstanceApi,
  OpenSubfieldApi,
  RefDefineApi,
  WireApi
}
import org.llvm.mlir.scalalib.capi.ir.{
  given_AttributeApi as mlirGivenAttributeApi,
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
  Operation
}

import java.lang.foreign.Arena
import java.nio.file.StandardOpenOption.*

object BaseGeneratorHelper:
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
    instanceOp.operation.appendToBlock()
    val probeWire   = summon[WireApi].op(
      s"${valName}_probe",
      summon[LocationApi].locationUnknownGet,
      FirrtlNameKind.Droppable,
      probeTpe.toMlirType
    )
    probeWire.operation.appendToBlock()

    probeFields.zipWithIndex.foreach: (field, idx) =>
      val instanceIO = instanceOp.operation.getResult(ioFields.length + idx)
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
      val _operation = instanceOp.operation
      val _io        = new Interface[I](ioTpe, IArray.tabulate(ioFields.length)(idx => instanceOp.operation.getResult(idx)))
      val _probeWire = new Wire[P]:
        private[zaozi] val _tpe   = probeTpe
        private[zaozi] val _refer = probeWire.operation.getResult(0)

  def dumpMlirbc[PARAM <: Parameter](
    moduleName:        String,
    elaboratedModules: scala.collection.mutable.HashSet[(PARAM, os.Path)],
    parameter:         PARAM,
    createModule:      (Arena, Context, Circuit) => Unit
  )(
    using Arena,
    Context
  ): Unit =
    val outputDirectory = Elaboration.outputDirectory
    val elaborationKey  = parameter -> outputDirectory
    if !elaboratedModules.contains(elaborationKey) then
      given MlirModule = summon[MlirModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
      given Circuit    = summon[CircuitApi].op(moduleName)
      summon[Circuit].appendToModule()
      createModule(summon[Arena], summon[Context], summon[Circuit])
      me.jiuyang.zaozi.magic.validateCircuit()

      os.makeDir.all(outputDirectory)
      val mlirbcFile = outputDirectory / s"${moduleName}.mlirbc"
      val out        = os.write.outputStream(mlirbcFile, openOptions = Seq(WRITE, CREATE, TRUNCATE_EXISTING))
      summon[MlirModule].getOperation.writeBytecode(bc => out.write(bc))
      elaboratedModules.add(elaborationKey)

end BaseGeneratorHelper
