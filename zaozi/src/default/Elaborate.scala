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

import scala.scalanative.unsafe.{extern, name, toCString, CString, Zone}
import scala.scalanative.unsafe.Ptr
import org.llvm.mlir.raw.structs.MlirOperation

@extern
private object BytecodeFile:
  @name("zaozi_write_bytecode_file")
  def write(operation: Ptr[MlirOperation], path: CString): Int = extern

/** Where elaborated modules go, and elaborating a generator into one linked circuit in memory. */
object Elaborate:
  /** Where elaborated modules go, by module name. A module already there is not elaborated again. */
  private[default] trait Modules:
    // Collected MLIR handles continue to use the caller's zone.
    def withModuleStorage[A](
      body: Zone ?=> A
    )(
      using Zone
    ): A = body
    def contains(name: String): Boolean
    def add(
      name:   String,
      module: MlirModule
    )(
      using Zone
    ):                          Unit

  /** Modules written as mlirbc files under `ZAOZI_OUTDIR`, once per name in the current elaboration. */
  private final class Files extends Modules:
    override def withModuleStorage[A](
      body: Zone ?=> A
    )(
      using Zone
    ): A =
      org.llvm.mlir.scalalib.Mlir.withZone(body)
    private val written = scala.collection.mutable.HashSet.empty[String]
    def contains(name: String): Boolean = written.contains(name)
    def add(
      name:   String,
      module: MlirModule
    )(
      using Zone
    ): Unit =
      val file      = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", ""), os.pwd) / s"$name.mlirbc"
      // Let fopen create new output with the caller's umask inside a private directory.
      val staging   = os.temp.dir(dir = file / os.up, prefix = ".zaozi-", deleteOnExit = false)
      val temporary = staging / "module.mlirbc"
      try
        val error = BytecodeFile.write(module.getOperation.native, toCString(temporary.toString))
        if error != 0 then throw new java.io.IOException(s"Cannot write $file (errno $error)")
        java.nio.file.Files.move(
          temporary.toNIO,
          file.toNIO,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
      finally os.remove.all(staging)
      written.add(name)
      module.destroy()

  private val current = new DynamicVariable[Option[Modules]](None)

  private[default] def withModules[A](body: Modules => A): A = current.value match
    case Some(modules) => body(modules)
    case None          =>
      val files = new Files
      current.withValue(Some(files))(body(files))

  def apply[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    generator: Generator[PARAM, L, I, P],
    parameter: PARAM
  )(
    using Zone,
    Context
  ): MlirModule =
    val table = scala.collection.mutable.LinkedHashMap.empty[String, MlirModule]
    try
      collect(table)(generator.dumpMlirbc(parameter))
      val linked = summon[MlirModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
      try
        table.valuesIterator.foreach: module =>
          val circuit = module.getBody.getFirstOperation
          circuit.removeFromParent()
          linked.getBody.appendOwnedOperation(circuit)
        val manager = summon[PassManagerApi].passManagerCreate
        try
          manager.addOwnedPass(summon[PassApi].linkCircuitsPass(generator.moduleName(parameter), noMangle = false))
          require(manager.runOnOp(linked.getOperation).succeeded, s"cannot link ${generator.moduleName(parameter)}")
        finally manager.destroy()
        linked
      catch
        case t: Throwable =>
          linked.destroy()
          throw t
    finally table.valuesIterator.foreach(_.destroy())

  /** Runs `body`, adding to `table` every module it elaborates, each as a circuit of its own, by module name. A module
    * already in `table` is not elaborated again. The modules belong to the context they were elaborated in, so a table
    * is reused only within one context.
    */
  def collect[A](table: scala.collection.mutable.LinkedHashMap[String, MlirModule])(body: => A): A =
    val modules = new Modules:
      def contains(name: String): Boolean = table.contains(name)
      def add(
        name:   String,
        module: MlirModule
      )(
        using Zone
      ): Unit = table.update(name, module)
    current.withValue(Some(modules))(body)
end Elaborate
