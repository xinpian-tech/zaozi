// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.magic.UntypedDynamicSubfield
import me.jiuyang.zaozi.reftpe.Ref
import me.jiuyang.zaozi.{LayerTree, TypeImpl}

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Type, Value}

import java.lang.foreign.Arena

trait ProbeRecord extends Aggregate with UntypedDynamicSubfield:
  def ProbeRead[T <: Data & CanProbe](
    name:  String,
    tpe:   T,
    layer: LayerTree
  )(
    using TypeImpl
  ): BundleField[RProbe[T]] =
    this.ReadProbeImpl(name, tpe, layer)

  def ProbeReadWrite[T <: Data & CanProbe](
    name:  String,
    tpe:   T,
    layer: LayerTree
  )(
    using TypeImpl
  ): BundleField[RWProbe[T]] =
    this.ReadWriteProbeImpl(name, tpe, layer)

  def getUntypedRefViaFieldValName(
    refer:        Value,
    fieldValName: String
  )(
    using Arena,
    Block,
    Context,
    sourcecode.File,
    sourcecode.Line
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
