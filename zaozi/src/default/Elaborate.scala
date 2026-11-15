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
import java.nio.file.StandardOpenOption.*

/** Where elaborated modules go, and elaborating a generator into one linked circuit in memory. */
object Elaborate:
  /** Where elaborated modules go, by module name. A module already there is not elaborated again. */
  private[default] trait Modules:
    def contains(name: String): Boolean
    def add(
      name:   String,
      module: MlirModule
    )(
      using Arena
    ):                          Unit

  /** Modules written as mlirbc files under `ZAOZI_OUTDIR`, once per name in this process. */
  private object Files extends Modules:
    private val written = scala.collection.mutable.HashSet.empty[String]
    def contains(name: String): Boolean = written.contains(name)
    def add(
      name:   String,
      module: MlirModule
    )(
      using Arena
    ): Unit =
      val file = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", ""), os.pwd) / s"$name.mlirbc"
      val out  = os.write.outputStream(file, openOptions = Seq(WRITE, CREATE, TRUNCATE_EXISTING))
      module.getOperation.writeBytecode(bc => out.write(bc))
      written.add(name)

  /** Where the modules elaborated now go: files, or the table of the enclosing `collect`. */
  private[default] val modules = DynamicVariable[Modules](Files)

  def apply[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    generator: Generator[PARAM, L, I, P],
    parameter: PARAM
  )(
    using Arena,
    Context
  ): MlirModule =
    val table  = scala.collection.mutable.LinkedHashMap.empty[String, MlirModule]
    collect(table)(generator.dumpMlirbc(parameter))
    val linked = summon[MlirModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
    table.foreach: (_, module) =>
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

  /** Runs `body`, adding to `table` every module it elaborates, each as a circuit of its own, by module name. A module
    * already in `table` is not elaborated again. The modules belong to the context they were elaborated in, so a table
    * is reused only within one context.
    */
  def collect[A](table: scala.collection.mutable.LinkedHashMap[String, MlirModule])(body: => A): A =
    modules.withValue(
      new Modules:
        def contains(name: String): Boolean = table.contains(name)
        def add(
          name:   String,
          module: MlirModule
        )(
          using Arena
        ): Unit = table.update(name, module)
    )(body)
end Elaborate
