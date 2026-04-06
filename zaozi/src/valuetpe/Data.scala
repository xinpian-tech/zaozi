// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.TypeImpl
import me.jiuyang.zaozi.magic.{DynamicSubfield, UntypedDynamicSubfield}

import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import java.lang.foreign.Arena

trait Data:
  // toMlirType is called lazily when constructing MLIR operations.
  // This design requires maintaining type metadata (e.g., _width) in Scala objects.
  // These fields should ONLY be accessed in toMlirTypeImpl for MLIR Type construction.
  // To query type information from existing operations, use methods like ref.width
  // which retrieve data directly from MLIR instead of the cached Scala fields.
  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type
  def width(
    using Arena,
    Context,
    TypeImpl
  ): Int = this.widthImpl

trait Width:
  val _width: Int

trait Aggregate extends Data:
  this: DynamicSubfield | UntypedDynamicSubfield =>
  private[zaozi] var instantiating = true
  private[zaozi] val _elements     = collection.mutable.Buffer.empty[BundleField[?]]
