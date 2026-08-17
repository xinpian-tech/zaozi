// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.{LayerTree, TypeImpl}
import me.jiuyang.zaozi.magic.DynamicSubfield
import me.jiuyang.zaozi.reftpe.Ref
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Type, Value}

import java.lang.foreign.Arena

// The ProbeBundle is only defined at the interface of a module
trait ProbeBundle extends Aggregate with DynamicSubfield:
  def ProbeRead[T <: Data & CanProbe](
    tpe:   T,
    layer: LayerTree
  )(
    using TypeImpl,
    sourcecode.Name.Machine
  ): BundleField[RProbe[T]] =
    this.ReadProbeImpl(tpe, layer)

  def ProbeReadWrite[T <: Data & CanProbe](
    tpe:   T,
    layer: LayerTree
  )(
    using TypeImpl,
    sourcecode.Name.Machine
  ): BundleField[RWProbe[T]] =
    this.ReadWriteProbeImpl(tpe, layer)

  def getRefViaFieldValName[E <: Data](
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
  ): Ref[E] = this.getRefViaFieldValNameImpl(
    refer,
    fieldValName
  )

  def getOptionRefViaFieldValName[E <: Data](
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
  ): Option[Ref[E]] = this.getOptionRefViaFieldValNameImpl(
    refer,
    fieldValName
  )

  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
