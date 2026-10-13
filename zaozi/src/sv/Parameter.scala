// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv

import me.jiuyang.zaozi.default.bundleFieldName
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context}
import java.lang.foreign.Arena
import scala.language.dynamics

/** A declaration field, never a runtime reference. */
final case class ParameterField(name: String, default: Int, width: Int = 32)

abstract class VerilogParameterInterface:
  private[zaozi] val _parameters = collection.mutable.ArrayBuffer.empty[ParameterField]
  protected def VerilogParameter(
    default: Int,
    width:   Int = 32
  )(
    using sourcecode.Name.Machine
  ): ParameterField =
    val field = ParameterField(bundleFieldName, default, width)
    _parameters += field
    field

case class ParameterBinding private[sv] (name: String, value: ConstExpr)

final class ParameterInterface[V <: VerilogParameterInterface] private[sv] (
  val getType:              V,
  private[sv] val bindings: Map[String, ConstExpr] = Map.empty)
    extends scala.Dynamic:
  def declarations(
    using Arena,
    Context,
    TypeImpl
  ): Seq[Attribute] = getType._parameters.toSeq.map(_.declarationImpl)
  def field(name: String):                                   ParameterRef =
    new ParameterRef(
      getType._parameters
        .find(_.name == name)
        .getOrElse(throw new IllegalArgumentException(s"Unknown Verilog parameter: $name")),
      bindings.get(name)
    )
  transparent inline def selectDynamic(inline name: String): ParameterRef =
    ${ me.jiuyang.zaozi.sv.magic.parameterSelect('this, 'name) }

object ParameterInterface:
  def apply[V <: VerilogParameterInterface](declarations: V): ParameterInterface[V] =
    new ParameterInterface(declarations)

/** Concrete Verilog parameter bindings, exposed only to the FIRRTL interface adapter. */
final case class FIRRTLParameters[V <: VerilogParameterInterface] private[sv] (
  private val values: Map[String, Int])
    extends scala.Dynamic:
  def field(name: String):                                   Int = values(name)
  transparent inline def selectDynamic(inline name: String): Int =
    ${ me.jiuyang.zaozi.sv.magic.firrtlParameterSelect('this, 'name) }
