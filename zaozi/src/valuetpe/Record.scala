// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.TypeImpl
import me.jiuyang.zaozi.magic.UntypedDynamicSubfield
import me.jiuyang.zaozi.reftpe.Ref

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Type, Value}

import java.lang.foreign.Arena

trait Record extends Aggregate with Connectable with UntypedDynamicSubfield:
  def Flipped[T <: Data](
    name: String,
    tpe:  T
  )(
    using TypeImpl
  ): BundleField[T] = this.FlippedImpl(name, tpe)

  def Aligned[T <: Data](
    name: String,
    tpe:  T
  )(
    using TypeImpl
  ): BundleField[T] = this.AlignedImpl(name, tpe)

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
  ): Ref[Data] = this.getUntypedRefViaFieldValNameImpl(
    refer,
    fieldValName
  )

  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
