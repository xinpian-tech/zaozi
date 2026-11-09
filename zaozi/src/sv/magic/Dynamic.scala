// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.magic

import scala.quoted.*
import me.jiuyang.zaozi.sv.*
import me.jiuyang.zaozi.sv.valuetpe.Data

private def parameterSelectTyped[V <: VerilogParameterInterface: Type](
  ref:  Expr[ParameterInterface[V]],
  name: Expr[String]
)(
  using Quotes
): Expr[ParameterRef] =
  import quotes.reflect.*
  val field = TypeRepr.of[V].typeSymbol.fieldMember(name.valueOrAbort)
  if !field.exists || !(TypeRepr.of[V].memberType(field) <:< TypeRepr.of[ParameterField]) then
    report.errorAndAbort(s"${name.valueOrAbort} is not a Verilog parameter of ${Type.show[V]}")
  '{ $ref.field($name) }

private def interfaceSelectTyped[I <: SVInterface: Type](
  ref:  Expr[Interface[I]],
  name: Expr[String]
)(
  using Quotes
): Expr[Any] =
  import quotes.reflect.*
  val field = TypeRepr.of[I].typeSymbol.fieldMember(name.valueOrAbort)
  if !field.exists then report.errorAndAbort(s"Unknown SV interface field ${name.valueOrAbort}")
  TypeRepr.of[I].memberType(field).widenTermRefByName.dealias.asType match
    case '[Port[t]] => '{ $ref.field[t & Data]($name) }
    case _          => report.errorAndAbort(s"${name.valueOrAbort} is not an SV port")

private def firrtlParameterSelectTyped[V <: VerilogParameterInterface: Type](
  ref:  Expr[FIRRTLParameters[V]],
  name: Expr[String]
)(
  using Quotes
): Expr[Int] =
  import quotes.reflect.*
  val field = TypeRepr.of[V].typeSymbol.fieldMember(name.valueOrAbort)
  if !field.exists || !(TypeRepr.of[V].memberType(field) <:< TypeRepr.of[ParameterField]) then
    report.errorAndAbort(s"${name.valueOrAbort} is not a Verilog parameter of ${Type.show[V]}")
  '{ $ref.field($name) }

def parameterSelect(
  ref:  Expr[ParameterInterface[?]],
  name: Expr[String]
)(
  using Quotes
): Expr[ParameterRef] =
  import quotes.reflect.*
  ref.asTerm.tpe.baseType(TypeRepr.of[ParameterInterface].typeSymbol).asType match
    case '[ParameterInterface[t]] =>
      parameterSelectTyped[t & VerilogParameterInterface](
        ref.asExprOf[ParameterInterface[t & VerilogParameterInterface]],
        name
      )
    case _                        => report.errorAndAbort("Expected an SV declaration reference")

def interfaceSelect(
  ref:  Expr[Interface[?]],
  name: Expr[String]
)(
  using Quotes
): Expr[Any] =
  import quotes.reflect.*
  ref.asTerm.tpe.baseType(TypeRepr.of[Interface].typeSymbol).asType match
    case '[Interface[t]] => interfaceSelectTyped[t & SVInterface](ref.asExprOf[Interface[t & SVInterface]], name)
    case _               => report.errorAndAbort("Expected an SV declaration reference")

def firrtlParameterSelect(
  ref:  Expr[FIRRTLParameters[?]],
  name: Expr[String]
)(
  using Quotes
): Expr[Int] =
  import quotes.reflect.*
  ref.asTerm.tpe.baseType(TypeRepr.of[FIRRTLParameters].typeSymbol).asType match
    case '[FIRRTLParameters[t]] =>
      firrtlParameterSelectTyped[t & VerilogParameterInterface](
        ref.asExprOf[FIRRTLParameters[t & VerilogParameterInterface]],
        name
      )
    case _                      => report.errorAndAbort("Expected an SV declaration reference")

private def firrtlMacroSelectTyped[M <: VerilogMacroInterface: Type](
  ref:  Expr[FIRRTLMacros[M]],
  name: Expr[String]
)(
  using Quotes
): Expr[ResolvedMacro] =
  import quotes.reflect.*
  val field = TypeRepr.of[M].typeSymbol.fieldMember(name.valueOrAbort)
  if !field.exists || !(TypeRepr.of[M].memberType(field) <:< TypeRepr.of[MacroRef]) then
    report.errorAndAbort(s"${name.valueOrAbort} is not a Verilog macro of ${Type.show[M]}")
  '{ $ref.field($name) }

def firrtlMacroSelect(
  ref:  Expr[FIRRTLMacros[?]],
  name: Expr[String]
)(
  using Quotes
): Expr[ResolvedMacro] =
  import quotes.reflect.*
  ref.asTerm.tpe.baseType(TypeRepr.of[FIRRTLMacros].typeSymbol).asType match
    case '[FIRRTLMacros[m]] =>
      firrtlMacroSelectTyped[m & VerilogMacroInterface](ref.asExprOf[FIRRTLMacros[m & VerilogMacroInterface]], name)
    case _                  => report.errorAndAbort("Expected a concrete macro binding view")
