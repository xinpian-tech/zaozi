// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.zaozi.default

import scala.util.DynamicVariable

import me.jiuyang.zaozi.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.given_PassApi
import org.llvm.circt.scalalib.capi.dialect.firrtl.PassApi
import org.llvm.mlir.scalalib.capi.ir.{
  given_BlockApi,
  given_LocationApi,
  given_ModuleApi,
  given_OperationApi,
  Context,
  LocationApi,
  Module as MlirModule,
  ModuleApi as MlirModuleApi
}
import org.llvm.mlir.scalalib.capi.pass.{given_PassManagerApi, PassManagerApi}
import org.llvm.mlir.scalalib.capi.support.given_LogicalResultApi

import java.lang.foreign.Arena

/** Elaborates a generator and every module it instantiates into one linked circuit in memory, writing no file. */
object Elaborate:
  /** Circuits built during one elaboration, by module name. */
  private[default] val collected =
    DynamicVariable[Option[scala.collection.mutable.LinkedHashMap[String, MlirModule]]](None)

  def apply[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    generator: Generator[PARAM, L, I, P],
    parameter: PARAM
  )(
    using Arena,
    Context
  ): MlirModule =
    val modules = scala.collection.mutable.LinkedHashMap.empty[String, MlirModule]
    collected.withValue(Some(modules))(generator.dumpMlirbc(parameter))
    val linked  = summon[MlirModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
    modules.values.foreach: module =>
      val circuit = module.getBody.getFirstOperation
      circuit.removeFromParent()
      linked.getBody.appendOwnedOperation(circuit)
    val passManager = summon[PassManagerApi].passManagerCreate
    passManager.addOwnedPass(summon[PassApi].linkCircuitsPass(generator.moduleName(parameter), noMangle = false))
    require(
      passManager.runOnOp(linked.getOperation).succeeded,
      s"cannot link the modules of ${generator.moduleName(parameter)}"
    )
    linked
end Elaborate
