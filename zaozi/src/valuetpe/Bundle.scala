// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.TypeImpl
import me.jiuyang.zaozi.magic.DynamicSubfield
import me.jiuyang.zaozi.reftpe.Ref
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Type, Value}

import java.lang.foreign.Arena

/** A statically-shaped aggregate whose fields are declared as `val`s of type `BundleField[T]` (typically built with
  * [[Flipped]]/[[Aligned]]), analogous to a FIRRTL bundle. Field names and directions are fixed at definition time,
  * unlike [[Record]] where fields are named dynamically at each declaration site. `HWBundle` (module IO) and plain
  * `Bundle` subclasses (nested aggregates) both extend this.
  */
trait Bundle extends Aggregate with Connectable with DynamicSubfield:
  /** Declares a field whose direction is the opposite of its enclosing bundle's -- an input when the bundle is an
    * output and vice versa.
    */
  def Flipped[T <: Data](
    tpe: T
  )(
    using TypeImpl,
    sourcecode.Name.Machine
  ): BundleField[T] = this.FlippedImpl(tpe)

  /** Declares a field whose direction matches its enclosing bundle's -- an output when the bundle is an output and vice
    * versa.
    */
  def Aligned[T <: Data](
    tpe: T
  )(
    using TypeImpl,
    sourcecode.Name.Machine
  ): BundleField[T] = this.AlignedImpl(tpe)

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
    sourcecode.Line,
    sourcecode.Name.Machine
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

/** One declared field of a [[Bundle]] or [[Record]]: its name, direction, and type.
  *
  * The documented exception to the "MLIR is the source of truth; only the Scala Data subtype is Scala-resident" rule
  * stated in [[Data]]. Of the three fields:
  *   - `dataType`: MUST be Scala-resident; Scala `Data` subclasses cannot be reverse-derived from an MLIR Type.
  *   - `name` / `isFlipped`: construction inputs handed verbatim to MLIR; Scala-side and MLIR-side copies are 1:1
  *     mirrors after materialization, so exposing the Scala-side copies directly for `record.elements` introspection is
  *     correct without going through an MLIR query.
  */
case class BundleField[T <: Data](
  name:      String,
  isFlipped: Boolean,
  dataType:  T)
