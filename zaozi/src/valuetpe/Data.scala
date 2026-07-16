// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.TypeImpl
import me.jiuyang.zaozi.magic.{DynamicSubfield, UntypedDynamicSubfield}

import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import java.lang.foreign.Arena

/** Base type for every hardware value/type Zaozi can represent: [[Bits]], [[UInt]], [[SInt]], [[Bool]], [[Clock]],
  * [[Reset]], [[Analog]], aggregates ([[Bundle]], [[Record]], [[Vec]]), and probe reference types. A `Data` is a
  * type-level description (e.g. "a 32-bit UInt") that gets turned into an MLIR FIRRTL type on demand -- it does not by
  * itself refer to a value in the circuit; see [[me.jiuyang.zaozi.reftpe.Referable]] for that.
  */
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

/** Base type for `Data` with named sub-fields ([[Bundle]], [[Record]], [[ProbeBundle]], [[ProbeRecord]]), i.e. anything
  * whose fields are discovered dynamically by field access rather than being a plain scalar.
  */
trait Aggregate extends Data:
  this: DynamicSubfield | UntypedDynamicSubfield =>
  private[zaozi] var instantiating = true
  private[zaozi] val _elements     = collection.mutable.Buffer.empty[BundleField[?]]
