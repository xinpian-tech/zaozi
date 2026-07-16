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
  given_ConnectApi,
  given_InstanceApi,
  given_OpenSubfieldApi,
  given_RefDefineApi,
  given_SubfieldApi,
  given_WireApi,
  Circuit,
  CircuitApi,
  ConnectApi,
  InstanceApi,
  OpenSubfieldApi,
  RefDefineApi,
  SubfieldApi,
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

/** Shared implementation behind both `me.jiuyang.zaozi.default.GeneratorApi` (for `Generator`) and
  * `me.jiuyang.zaozi.default.VerilogWrapperApi` (for `VerilogWrapper`): building a FIRRTL `instance` op and writing a
  * module's elaborated MLIR bytecode to disk.
  */
object BaseGeneratorHelper:
  /** Builds a FIRRTL `firrtl.instance` of `moduleName` and wraps its IO/probe ports in fresh `io`/`probe` wires
    * (aligned fields connected instance-out-to-wire, flipped fields wire-to-instance-in), producing the [[Instance]]
    * returned by `instantiate`.
    */
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
    val ioWire      = summon[WireApi].op(
      s"${valName}_io",
      summon[LocationApi].locationUnknownGet,
      FirrtlNameKind.Droppable,
      ioTpe.toMlirType
    )
    ioWire.operation.appendToBlock()
    val probeWire   = summon[WireApi].op(
      s"${valName}_probe",
      summon[LocationApi].locationUnknownGet,
      FirrtlNameKind.Droppable,
      probeTpe.toMlirType
    )
    probeWire.operation.appendToBlock()

    ioFields.zipWithIndex.foreach:    (field, idx) =>
      val flip       = field.getIsFlip
      val instanceIO = instanceOp.operation.getResult(idx)
      val wireIO     = summon[SubfieldApi].op(
        ioWire.result,
        idx,
        locate
      )
      wireIO.operation.appendToBlock()
      val connect    =
        if (flip) summon[ConnectApi].op(wireIO.result, instanceIO, locate)
        else summon[ConnectApi].op(instanceIO, wireIO.result, locate)
      connect.operation.appendToBlock()
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
      val _ioWire    = new Wire[I]:
        private[zaozi] val _tpe       = ioTpe
        private[zaozi] val _operation = ioWire.operation
      val _probeWire = new Wire[P]:
        private[zaozi] val _tpe       = probeTpe
        private[zaozi] val _operation = probeWire.operation

  /** Elaborates `createModule` into a fresh circuit and writes its MLIR bytecode to `$ZAOZI_OUTDIR/moduleName.mlirbc`
    * (or `./moduleName.mlirbc` if unset) -- but only the first time `parameter` is seen (guarded by
    * `elaboratedModules`), so re-instantiating the same parameterization elsewhere in the design is a no-op.
    */
  def dumpMlirbc[PARAM <: Parameter](
    moduleName:        String,
    elaboratedModules: scala.collection.mutable.HashSet[PARAM],
    parameter:         PARAM,
    createModule:      (Arena, Context, Circuit) => Unit
  )(
    using Arena,
    Context
  ): Unit =
    if !elaboratedModules.contains(parameter) then
      given MlirModule = summon[MlirModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
      given Circuit    = summon[CircuitApi].op(moduleName)
      summon[Circuit].appendToModule()
      createModule(summon[Arena], summon[Context], summon[Circuit])
      me.jiuyang.zaozi.magic.validateCircuit()

      val mlirbcFile =
        os.Path(
          sys.env.getOrElse("ZAOZI_OUTDIR", ""),
          os.pwd
        ) / s"${moduleName}.mlirbc"
      val out        = os.write.outputStream(mlirbcFile, openOptions = Seq(WRITE, CREATE, TRUNCATE_EXISTING))
      summon[MlirModule].getOperation.writeBytecode(bc => out.write(bc))
      elaboratedModules.add(parameter)

end BaseGeneratorHelper
