// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.operation

import org.llvm.circt.scalalib.dialect.sv.attr.*
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context, Location, Operation, Type, Value}
import org.llvm.mlir.scalalib.capi.support.HasOperation
import java.lang.foreign.Arena

/** `sv.concat_str`. */
class ConcatStr(val _operation: Operation)
trait ConcatStrApi extends HasOperation[ConcatStr]:
  def op(
    inputs:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   ConcatStr
  extension (ref: ConcatStr)
    def result(
      using Arena
    ): Value
end ConcatStrApi

/** `sv.constantStr`. */
class ConstantStr(val _operation: Operation)
trait ConstantStrApi extends HasOperation[ConstantStr]:
  def op(
    str:         String,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   ConstantStr
  extension (ref: ConstantStr)
    def result(
      using Arena
    ): Value
end ConstantStrApi

/** `sv.constantX`. */
class ConstantX(val _operation: Operation)
trait ConstantXApi extends HasOperation[ConstantX]:
  def op(
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   ConstantX
  extension (ref: ConstantX)
    def result(
      using Arena
    ): Value
end ConstantXApi

/** `sv.constantZ`. */
class ConstantZ(val _operation: Operation)
trait ConstantZApi extends HasOperation[ConstantZ]:
  def op(
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   ConstantZ
  extension (ref: ConstantZ)
    def result(
      using Arena
    ): Value
end ConstantZApi

/** `sv.indexed_part_select`. */
class IndexedPartSelect(val _operation: Operation)
trait IndexedPartSelectApi extends HasOperation[IndexedPartSelect]:
  def op(
    input:       Value,
    base:        Value,
    width:       Int,
    decrement:   Boolean = false,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   IndexedPartSelect
  extension (ref: IndexedPartSelect)
    def result(
      using Arena
    ): Value
end IndexedPartSelectApi

/** `sv.localparam`. */
class LocalParam(val _operation: Operation)
trait LocalParamApi extends HasOperation[LocalParam]:
  def op(
    value:       Attribute,
    name:        String,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   LocalParam
  extension (ref: LocalParam)
    def result(
      using Arena
    ): Value
end LocalParamApi

/** `sv.macro.ref.expr`. */
class MacroRefExpr(val _operation: Operation)
trait MacroRefExprApi extends HasOperation[MacroRefExpr]:
  def op(
    macroName:   String,
    inputs:      Seq[Value] = Seq.empty,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   MacroRefExpr
  extension (ref: MacroRefExpr)
    def result(
      using Arena
    ): Value
end MacroRefExprApi

/** `sv.macro.ref.expr.se`. */
class MacroRefExprSE(val _operation: Operation)
trait MacroRefExprSEApi extends HasOperation[MacroRefExprSE]:
  def op(
    macroName:   String,
    inputs:      Seq[Value] = Seq.empty,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   MacroRefExprSE
  extension (ref: MacroRefExprSE)
    def result(
      using Arena
    ): Value
end MacroRefExprSEApi

/** `sv.sformatf`. */
class SFormatF(val _operation: Operation)
trait SFormatFApi extends HasOperation[SFormatF]:
  def op(
    formatString:  String,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ):   SFormatF
  extension (ref: SFormatF)
    def result(
      using Arena
    ): Value
end SFormatFApi

/** `sv.system`. */
class SystemFunction(val _operation: Operation)
trait SystemFunctionApi extends HasOperation[SystemFunction]:
  def op(
    fnName:      String,
    args:        Seq[Value] = Seq.empty,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   SystemFunction
  extension (ref: SystemFunction)
    def result(
      using Arena
    ): Value
end SystemFunctionApi

/** `sv.system.sampled`. */
class Sampled(val _operation: Operation)
trait SampledApi extends HasOperation[Sampled]:
  def op(
    expression:  Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   Sampled
  extension (ref: Sampled)
    def result(
      using Arena
    ): Value
end SampledApi

/** `sv.system.stime`. */
class STime(val _operation: Operation)
trait STimeApi extends HasOperation[STime]:
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   STime
  extension (ref: STime)
    def result(
      using Arena
    ): Value
end STimeApi

/** `sv.system.time`. */
class Time(val _operation: Operation)
trait TimeApi extends HasOperation[Time]:
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   Time
  extension (ref: Time)
    def result(
      using Arena
    ): Value
end TimeApi

/** `sv.unpacked_array_create`. */
class UnpackedArrayCreate(val _operation: Operation)
trait UnpackedArrayCreateApi extends HasOperation[UnpackedArrayCreate]:
  def op(
    inputs:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): UnpackedArrayCreate

  /** Explicit result type, including an HW type alias of the unpacked array. */
  def op(
    inputs:      Seq[Value],
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   UnpackedArrayCreate
  extension (ref: UnpackedArrayCreate)
    def result(
      using Arena
    ): Value
end UnpackedArrayCreateApi

/** `sv.unpacked_open_array_cast`. */
class UnpackedOpenArrayCast(val _operation: Operation)

/** Cast an unpacked array to an open array with the same element type. The result type is explicit because the pinned C
  * API does not expose unpacked-array element access.
  */
trait UnpackedOpenArrayCastApi extends HasOperation[UnpackedOpenArrayCast]:
  def op(
    input:       Value,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   UnpackedOpenArrayCast
  extension (ref: UnpackedOpenArrayCast)
    def result(
      using Arena
    ): Value
end UnpackedOpenArrayCastApi

/** `sv.verbatim.expr`. */
class VerbatimExpr(val _operation: Operation)
trait VerbatimExprApi extends HasOperation[VerbatimExpr]:
  def op(
    formatString:  String,
    substitutions: Seq[Value] = Seq.empty,
    symbols:       Seq[Attribute] = Seq.empty,
    resultType:    Type,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ):   VerbatimExpr
  extension (ref: VerbatimExpr)
    def result(
      using Arena
    ): Value
end VerbatimExprApi

/** `sv.verbatim.expr.se`. */
class VerbatimExprSE(val _operation: Operation)
trait VerbatimExprSEApi extends HasOperation[VerbatimExprSE]:
  def op(
    formatString:  String,
    substitutions: Seq[Value] = Seq.empty,
    symbols:       Seq[Attribute] = Seq.empty,
    resultType:    Type,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ):   VerbatimExprSE
  extension (ref: VerbatimExprSE)
    def result(
      using Arena
    ): Value
end VerbatimExprSEApi
