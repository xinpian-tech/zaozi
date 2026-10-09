// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.zaozi.default

import scala.collection.mutable
import scala.util.DynamicVariable

import org.llvm.mlir.scalalib.capi.ir.Module as MlirModule

/** Elaboration that keeps module definitions in memory instead of writing a file per module. The collection mechanism
  * is zaozi PR #159's; #159 also links the collected circuits with CIRCT's link-circuits pass, which this CIRCT lacks.
  */
object Elaborate:
  /** Circuits built during one elaboration, by module name. */
  private[default] val collected = DynamicVariable[Option[mutable.LinkedHashMap[String, MlirModule]]](None)

  /** Runs `body`, keeping every module it elaborates, each as a circuit of its own, by module name. */
  def collect[A](body: => A): (A, Vector[(String, MlirModule)]) =
    val modules = mutable.LinkedHashMap.empty[String, MlirModule]
    val result  = collected.withValue(Some(modules))(body)
    (result, modules.toVector)
end Elaborate
