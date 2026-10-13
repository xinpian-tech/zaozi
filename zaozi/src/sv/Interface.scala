// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv

import me.jiuyang.zaozi.default.bundleFieldName
import me.jiuyang.zaozi.sv.valuetpe.Data
import me.jiuyang.zaozi.sv.reftpe.Ref
import org.llvm.circt.scalalib.dialect.hw.tpe.PortDirection
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Value}
import java.lang.foreign.Arena
import scala.language.dynamics

case class Port[T <: Data](name: String, dataType: T, direction: PortDirection):
  def bind(value: me.jiuyang.zaozi.sv.reftpe.Referable[T]): PortBinding = PortBinding(name, value)
case class PortBinding private[sv] (name: String, value: me.jiuyang.zaozi.sv.reftpe.Referable[?])

abstract class SVInterface:
  private[zaozi] val _ports = collection.mutable.ArrayBuffer.empty[Port[?]]
  protected def Flipped[T <: Data](
    tpe: T
  )(
    using sourcecode.Name.Machine
  ): Port[T] = port(tpe, PortDirection.Input)
  protected def Aligned[T <: Data](
    tpe: T
  )(
    using sourcecode.Name.Machine
  ): Port[T] = port(tpe, PortDirection.Output)
  protected def InOut[T <: Data](
    tpe: T
  )(
    using sourcecode.Name.Machine
  ): Port[T] = port(tpe, PortDirection.InOut)
  private def port[T <: Data](
    tpe:       T,
    direction: PortDirection
  )(
    using sourcecode.Name.Machine
  ): Port[T] =
    val field = Port(bundleFieldName, tpe, direction)
    _ports += field
    field

abstract class SVBundle extends SVInterface

final class Interface[I <: SVInterface] private[sv] (
  val getType:          I,
  private val values:   Map[String, Value],
  private val bindings: Map[String, ConstExpr] = Map.empty)
    extends Dynamic:
  def field[T <: Data](name: String):                        Ref[T] =
    val port =
      getType._ports.find(_.name == name).getOrElse(throw new IllegalArgumentException(s"Unknown SV port: $name"))
    new Ref[T]:
      override private[zaozi] val _bindings = bindings
      private[zaozi] val _tpe               = port.dataType.asInstanceOf[T]
      private[zaozi] val _refer             = values(name)
  transparent inline def selectDynamic(inline name: String): Any    =
    ${ me.jiuyang.zaozi.sv.magic.interfaceSelect('this, 'name) }

  transparent inline def applyDynamic(
    inline name: String
  )(index:       me.jiuyang.zaozi.sv.reftpe.Referable[me.jiuyang.zaozi.sv.valuetpe.Bits]
  )(
    using arena: Arena,
    context:     Context,
    block:       Block,
    impl:        TypeImpl,
    api:         AggregateApi,
    file:        sourcecode.File,
    line:        sourcecode.Line
  ): Any =
    ${
      me.jiuyang.zaozi.sv.magic
        .interfaceIndex('this, 'name, 'index)('arena, 'context, 'block, 'impl, 'api, 'file, 'line)
    }
