// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package fixtures

import me.jiuyang.zaozi.{InstanceContext, TypeImpl}
import me.jiuyang.zaozi.reftpe.{Interface, Referable}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Dynamic bundle-field accesses; the bodies never run, they only need to typecheck so that the
  * `selectDynamic` macro expands and SemanticDB is emitted for the access sites.
  */
object PluginFixtureUses:
  def throughReferable(
    io: Referable[OuterBundle]
  )(
    using Arena,
    Block,
    Context,
    TypeImpl,
    InstanceContext
  ): Unit =
    val innerRef = io.inner
    val dataRef  = io.data
    val flagRef  = io.inner.flag
    val maybeRef = io.maybe
    ()

  def throughInterface(
    io: Interface[FixtureIO]
  )(
    using Arena,
    Block,
    Context,
    TypeImpl,
    InstanceContext
  ): Unit =
    val a = io.bundle.input
    val b = io.nested.data
    val c = io.out
    ()
