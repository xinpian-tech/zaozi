// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.valuetpe

import me.jiuyang.zaozi.default.bundleFieldName
import me.jiuyang.zaozi.sv.TypeImpl
import me.jiuyang.zaozi.sv.reftpe.Referable
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}
import java.lang.foreign.Arena

final case class Field[T <: Data](name: String, dataType: T):
  def bind(value: Referable[T]): FieldBinding = FieldBinding(name, value)
final case class FieldBinding private[sv] (name: String, value: Referable[?])

abstract class Aggregate extends Data:
  private[zaozi] val _fields = collection.mutable.ArrayBuffer.empty[Field[?]]
  protected def Field[T <: Data](
    tpe: T
  )(
    using sourcecode.Name.Machine
  ): Field[T] =
    val field = new Field(bundleFieldName, tpe)
    _fields += field
    field

/** A SystemVerilog packed struct, with fields in declaration order. */
abstract class Struct extends Aggregate:
  private[sv] def rawType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl

/** A SystemVerilog packed union. Assign whole values using UnionValue; member selection reads a view. */
abstract class Union extends Aggregate:
  private[sv] def rawType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
