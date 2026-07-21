// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.magic

import me.jiuyang.zaozi.reftpe.Ref
import me.jiuyang.zaozi.valuetpe.Data
import me.jiuyang.zaozi.TypeImpl
import org.llvm.circt.scalalib.dialect.firrtl.operation.Module as CirctModule
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Operation, Type, Value}

import java.lang.foreign.Arena
import scala.language.dynamics

/** Due to Scala not allowing deferred macro call(calling user defined macro from outer macro). Any implementation to
  * [[DynamicSubfield]] should make sure the dynamic access is to a val that has a return type of
  * [[me.jiuyang.zaozi.valuetpe.BundleField BundleField]]. For now jiuyang cannot come up with better solution to let
  * user define their own macro, however they can still implement their own [[me.jiuyang.zaozi.valuetpe.Bundle Bundle]].
  */
trait DynamicSubfield:
  def getRefViaFieldValName[E <: Data](
    refer:        Value,
    fieldValName: String
  )(
    using Arena,
    Block,
    Context,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  )(
    using TypeImpl
  ): Ref[E]

  def getOptionRefViaFieldValName[E <: Data](
    refer:        Value,
    fieldValName: String
  )(
    using Arena,
    Block,
    Context,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  )(
    using TypeImpl
  ): Option[Ref[E]]

trait UntypedDynamicSubfield:
  def getUntypedRefViaFieldValName(
    refer:        Value,
    fieldValName: String
  )(
    using Arena,
    Block,
    Context,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  )(
    using TypeImpl
  ): Ref[Data]
