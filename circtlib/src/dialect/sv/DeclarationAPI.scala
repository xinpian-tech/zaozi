// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.operation

import org.llvm.circt.scalalib.dialect.sv.attr.*
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Location, Operation, Type, Value}
import org.llvm.mlir.scalalib.capi.support.HasOperation
import java.lang.foreign.Arena

/** `sv.array_index_inout`. */
class ArrayIndexInOut(val _operation: Operation)

/** Index a packed or unpacked array handle. resultType must be an inout of the array element type; the pinned C API has
  * no general unpacked-array element accessor.
  */
trait ArrayIndexInOutApi extends HasOperation[ArrayIndexInOut]:
  def op(
    input:       Value,
    index:       Value,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   ArrayIndexInOut
  extension (ref: ArrayIndexInOut)
    def result(
      using Arena
    ): Value
end ArrayIndexInOutApi

/** `sv.indexed_part_select_inout`. */
class IndexedPartSelectInOut(val _operation: Operation)
trait IndexedPartSelectInOutApi extends HasOperation[IndexedPartSelectInOut]:
  def op(
    input:       Value,
    base:        Value,
    width:       Int,
    decrement:   Boolean = false,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   IndexedPartSelectInOut
  extension (ref: IndexedPartSelectInOut)
    def result(
      using Arena
    ): Value
end IndexedPartSelectInOutApi

/** `sv.logic`. */
class Logic(val _operation: Operation)
trait LogicApi extends HasOperation[Logic]:
  def op(
    elementType: Type,
    name:        String = "",
    innerSym:    Option[Attribute] = None,
    comment:     Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   Logic
  extension (ref: Logic)
    def result(
      using Arena
    ): Value
end LogicApi

/** `sv.read_inout`. */
class ReadInOut(val _operation: Operation)
trait ReadInOutApi extends HasOperation[ReadInOut]:
  def op(
    input:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   ReadInOut
  extension (ref: ReadInOut)
    def result(
      using Arena
    ): Value
end ReadInOutApi

/** `sv.reg`. */
class Reg(val _operation: Operation)
trait RegApi extends HasOperation[Reg]:
  def op(
    elementType: Type,
    name:        String = "",
    init:        Option[Value] = None,
    innerSym:    Option[Attribute] = None,
    comment:     Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   Reg
  extension (ref: Reg)
    def result(
      using Arena
    ): Value
end RegApi

/** `sv.struct_field_inout`. */
class StructFieldInOut(val _operation: Operation)
trait StructFieldInOutApi extends HasOperation[StructFieldInOut]:
  def op(
    input:       Value,
    field:       String,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   StructFieldInOut
  extension (ref: StructFieldInOut)
    def result(
      using Arena
    ): Value
end StructFieldInOutApi

/** `sv.wire`. */
class Wire(val _operation: Operation)
trait WireApi extends HasOperation[Wire]:
  def op(
    elementType: Type,
    name:        String = "",
    innerSym:    Option[Attribute] = None,
    comment:     Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   Wire
  extension (ref: Wire)
    def result(
      using Arena
    ): Value
end WireApi

/** `sv.xmr`. */
class XMR(val _operation: Operation)
trait XMRApi extends HasOperation[XMR]:
  def op(
    isRooted:    Boolean = false,
    path:        Seq[String],
    terminal:    String,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   XMR
  extension (ref: XMR)
    def result(
      using Arena
    ): Value
end XMRApi

/** `sv.xmr.ref`. */
class XMRRef(val _operation: Operation)
trait XMRRefApi extends HasOperation[XMRRef]:
  def op(
    reference:      String,
    verbatimSuffix: String = "",
    resultType:     Type,
    location:       Location
  )(
    using arena:    Arena,
    context:        Context
  ):   XMRRef
  extension (ref: XMRRef)
    def result(
      using Arena
    ): Value
end XMRRefApi
