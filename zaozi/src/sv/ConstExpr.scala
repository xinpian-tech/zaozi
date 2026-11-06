// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv

import org.llvm.circt.scalalib.dialect.hw.attr.ParamExpression
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context}
import java.lang.foreign.Arena

/** A Verilog elaboration-time integer expression built from literals, parameters and macro values. Used for datatype
  * dimensions, localparam initializers, generate controls and instance bindings. Runtime signal expressions and Scala
  * generator Parameters are separate APIs.
  */
sealed trait ConstExpr:
  def toAttribute(
    using Arena,
    Context,
    TypeImpl
  ): Attribute = this.toAttributeImpl
  def +(rhs:  ConstExpr): ConstExpr = ConstExpr.Expression(ParamExpression.Add, Seq(this, rhs))
  def *(rhs:  ConstExpr): ConstExpr = ConstExpr.Expression(ParamExpression.Mul, Seq(this, rhs))
  def unary_-           : ConstExpr = this * ConstExpr(-1)
  def -(rhs:  ConstExpr): ConstExpr = this + (-rhs)
  def /(rhs:  ConstExpr): ConstExpr = ConstExpr.Expression(ParamExpression.DivU, Seq(this, rhs))
  def %(rhs:  ConstExpr): ConstExpr = ConstExpr.Expression(ParamExpression.ModU, Seq(this, rhs))
  def <<(rhs: ConstExpr): ConstExpr = ConstExpr.Expression(ParamExpression.Shl, Seq(this, rhs))
  def >>(rhs: ConstExpr): ConstExpr = ConstExpr.Expression(ParamExpression.ShrU, Seq(this, rhs))
  def &(rhs:  ConstExpr): ConstExpr = ConstExpr.Expression(ParamExpression.And, Seq(this, rhs))
  def |(rhs:  ConstExpr): ConstExpr = ConstExpr.Expression(ParamExpression.Or, Seq(this, rhs))
  def ^(rhs:  ConstExpr): ConstExpr = ConstExpr.Expression(ParamExpression.Xor, Seq(this, rhs))
  def clog2:              ConstExpr = ConstExpr.Expression(ParamExpression.CLog2, Seq(this))

object ConstExpr:
  def apply(value: Int): ConstExpr = Literal(value)
  private[zaozi] case class MacroValue(name: String) extends ConstExpr
  private[zaozi] case class Literal(value: Int) extends ConstExpr
  private[zaozi] case class Expression(opcode: ParamExpression, operands: Seq[ConstExpr]) extends ConstExpr

/** A reference to one declared Verilog parameter. It is usable both in type widths and as an expression. */
final class ParameterRef private[sv] (
  private[sv] val field:   ParameterField,
  private[sv] val binding: Option[ConstExpr] = None)
    extends ConstExpr,
      me.jiuyang.zaozi.sv.reftpe.Referable[me.jiuyang.zaozi.sv.valuetpe.Bits]:
  private[zaozi] val _tpe = new me.jiuyang.zaozi.sv.valuetpe.Bits:
    private[zaozi] val _width = ConstExpr(field.width)
  def bind(value: ConstExpr): ParameterBinding = ParameterBinding(field.name, value)
