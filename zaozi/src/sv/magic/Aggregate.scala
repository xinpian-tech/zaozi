// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.magic

import scala.quoted.*
import me.jiuyang.zaozi.sv.*
import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import java.lang.foreign.Arena

private def aggregateSelectTyped[T <: Data: Type](
  ref:      Expr[Referable[T]],
  name:     Expr[String],
  writable: Boolean
)(arena:    Expr[Arena],
  context:  Expr[Context],
  block:    Expr[Block],
  impl:     Expr[TypeImpl],
  api:      Expr[AggregateApi],
  file:     Expr[sourcecode.File],
  line:     Expr[sourcecode.Line]
)(
  using Quotes
): Expr[Any] =
  import quotes.reflect.*
  def underlying(tpe: TypeRepr): TypeRepr = tpe.dealias.asType match
    case '[TypeAlias[t]] => underlying(TypeRepr.of[t])
    case '[Modport[i]]   => TypeRepr.of[i]
    case _               => tpe.dealias
  val aggregate = underlying(TypeRepr.of[T])
  val member = aggregate.typeSymbol.fieldMember(name.valueOrAbort)
  if !member.exists then report.errorAndAbort(s"Unknown aggregate field ${name.valueOrAbort}")
  aggregate.memberType(member).widenTermRefByName.dealias.asType match
    case '[InterfaceSignal[e]] =>
      '{
        $api.interfaceField[T, e & Data]($ref, $name)(
          using $arena,
          $context,
          $block,
          $impl,
          $file,
          $line
        )
      }
    case '[Modport[i]]         =>
      '{
        $api.interfaceModport[T, i & VerilogInterface]($ref, $name)(
          using $arena,
          $context,
          $block,
          $impl,
          $file,
          $line
        )
      }
    case '[Field[e]]           =>
      if writable && aggregate <:< TypeRepr.of[Struct] then
        '{
          $api.fieldRef[T, e & Data]($ref.asInstanceOf[me.jiuyang.zaozi.sv.reftpe.Ref[T]], $name)(
            using $arena,
            $context,
            $block,
            $impl,
            $file,
            $line
          )
        }
      else
        '{
          $api.fieldValue[T, e & Data]($ref, $name)(
            using $arena,
            $context,
            $block,
            $impl,
            $file,
            $line
          )
        }
    case _                     => report.errorAndAbort(s"${name.valueOrAbort} is not an SV aggregate field")

private def aggregateIndexTyped[T <: Data: Type](
  ref:      Expr[Referable[T]],
  index:    Expr[Referable[Bits]],
  writable: Boolean
)(arena:    Expr[Arena],
  context:  Expr[Context],
  block:    Expr[Block],
  impl:     Expr[TypeImpl],
  api:      Expr[AggregateApi],
  file:     Expr[sourcecode.File],
  line:     Expr[sourcecode.Line]
)(
  using Quotes
): Expr[Any] =
  import quotes.reflect.*
  def element(tpe: TypeRepr): TypeRepr = tpe.dealias.asType match
    case '[TypeAlias[t]]     => element(TypeRepr.of[t])
    case '[PackedArray[e]]   => TypeRepr.of[e]
    case '[UnpackedArray[e]] => TypeRepr.of[e]
    case _                   => report.errorAndAbort("Indexing requires an SV array")
  element(TypeRepr.of[T]).asType match
    case '[e] =>
      if writable then
        '{
          $api.indexRef[T, e & Data]($ref.asInstanceOf[me.jiuyang.zaozi.sv.reftpe.Ref[T]], $index)(
            using $arena,
            $context,
            $block,
            $impl,
            $file,
            $line
          )
        }
      else
        '{
          $api.indexValue[T, e & Data]($ref, $index)(
            using $arena,
            $context,
            $block,
            $impl,
            $file,
            $line
          )
        }

private def aggregateApplyTyped[T <: Data: Type](
  ref:      Expr[Referable[T]],
  name:     Expr[String],
  index:    Expr[Referable[Bits]],
  writable: Boolean
)(arena:    Expr[Arena],
  context:  Expr[Context],
  block:    Expr[Block],
  impl:     Expr[TypeImpl],
  api:      Expr[AggregateApi],
  file:     Expr[sourcecode.File],
  line:     Expr[sourcecode.Line]
)(
  using Quotes
): Expr[Any] =
  import quotes.reflect.*
  val selected        = aggregateSelectTyped[T](ref, name, writable)(arena, context, block, impl, api, file, line)
  val writableElement = writable && selected.asTerm.tpe <:< TypeRepr.of[me.jiuyang.zaozi.sv.reftpe.Ref[?]]
  selected.asTerm.tpe.baseType(TypeRepr.of[Referable].typeSymbol).asType match
    case '[Referable[e]] =>
      aggregateIndexTyped[e & Data](selected.asExprOf[Referable[e & Data]], index, writableElement)(
        arena,
        context,
        block,
        impl,
        api,
        file,
        line
      )
    case _               => report.errorAndAbort("Indexing requires an SV array field")

def interfaceIndex(
  ref:     Expr[Interface[?]],
  name:    Expr[String],
  index:   Expr[Referable[Bits]]
)(arena:   Expr[Arena],
  context: Expr[Context],
  block:   Expr[Block],
  impl:    Expr[TypeImpl],
  api:     Expr[AggregateApi],
  file:    Expr[sourcecode.File],
  line:    Expr[sourcecode.Line]
)(
  using Quotes
): Expr[Any] =
  import quotes.reflect.*
  val selected = interfaceSelect(ref, name)
  selected.asTerm.tpe.baseType(TypeRepr.of[Referable].typeSymbol).asType match
    case '[Referable[e]] =>
      aggregateIndexTyped[e & Data](selected.asExprOf[Referable[e & Data]], index, true)(
        arena,
        context,
        block,
        impl,
        api,
        file,
        line
      )
    case _               => report.errorAndAbort("Indexing requires an SV array port")

// Inspect the quoted receiver instead of reifying singleton type arguments at the call site.
// This also permits types declared inside the same annotated generator as their uses.
def aggregateSelect(
  ref:      Expr[Referable[?]],
  name:     Expr[String],
  writable: Boolean
)(arena:    Expr[Arena],
  context:  Expr[Context],
  block:    Expr[Block],
  impl:     Expr[TypeImpl],
  api:      Expr[AggregateApi],
  file:     Expr[sourcecode.File],
  line:     Expr[sourcecode.Line]
)(
  using Quotes
): Expr[Any] =
  import quotes.reflect.*
  ref.asTerm.tpe.baseType(TypeRepr.of[Referable].typeSymbol).asType match
    case '[Referable[t]] =>
      aggregateSelectTyped[t & Data](ref.asExprOf[Referable[t & Data]], name, writable)(
        arena,
        context,
        block,
        impl,
        api,
        file,
        line
      )
    case _               => report.errorAndAbort("Expected an SV reference")

def aggregateIndex(
  ref:      Expr[Referable[?]],
  index:    Expr[Referable[Bits]],
  writable: Boolean
)(arena:    Expr[Arena],
  context:  Expr[Context],
  block:    Expr[Block],
  impl:     Expr[TypeImpl],
  api:      Expr[AggregateApi],
  file:     Expr[sourcecode.File],
  line:     Expr[sourcecode.Line]
)(
  using Quotes
): Expr[Any] =
  import quotes.reflect.*
  ref.asTerm.tpe.baseType(TypeRepr.of[Referable].typeSymbol).asType match
    case '[Referable[t]] =>
      aggregateIndexTyped[t & Data](ref.asExprOf[Referable[t & Data]], index, writable)(
        arena,
        context,
        block,
        impl,
        api,
        file,
        line
      )
    case _               => report.errorAndAbort("Expected an SV reference")

def aggregateApply(
  ref:      Expr[Referable[?]],
  name:     Expr[String],
  index:    Expr[Referable[Bits]],
  writable: Boolean
)(arena:    Expr[Arena],
  context:  Expr[Context],
  block:    Expr[Block],
  impl:     Expr[TypeImpl],
  api:      Expr[AggregateApi],
  file:     Expr[sourcecode.File],
  line:     Expr[sourcecode.Line]
)(
  using Quotes
): Expr[Any] =
  import quotes.reflect.*
  ref.asTerm.tpe.baseType(TypeRepr.of[Referable].typeSymbol).asType match
    case '[Referable[t]] =>
      aggregateApplyTyped[t & Data](ref.asExprOf[Referable[t & Data]], name, index, writable)(
        arena,
        context,
        block,
        impl,
        api,
        file,
        line
      )
    case _               => report.errorAndAbort("Expected an SV reference")
