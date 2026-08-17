// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.reftpe

import me.jiuyang.zaozi.HWInterface
import me.jiuyang.zaozi.valuetpe.{Data, Record}
import me.jiuyang.zaozi.magic.macros.{interfaceApplyDynamic, interfaceApplyDynamicNamed, interfaceSelectDynamic}
import org.llvm.mlir.scalalib.capi.ir.Value

import scala.language.dynamics

final class Interface[T <: HWInterface[?]] private[zaozi] (
  private[zaozi] val _tpe:   T,
  private[zaozi] val _ports: IArray[Value])
    extends Writable[T],
      Dynamic:

  def getType: T = _tpe

  private[zaozi] def portRef(index: Int): Ref[Data] =
    val bundleField = _tpe._elements(index)
    new Ref[Data]:
      val _tpe:   Data  = bundleField.dataType
      val _refer: Value = _ports(index)

  private[zaozi] def indexOfOption(name: String): Option[Int] =
    _tpe._elements.indexWhere(_.name == name) match
      case -1 => None
      case i  => Some(i)

  private[zaozi] def subRef(name: String): Ref[Data] =
    portRef(
      indexOfOption(name).getOrElse(
        throw new Exception(s"$name not found in ${_tpe._elements.map(_.name)}")
      )
    )

  private[zaozi] def subRefOption(name: String): Option[Ref[Data]] =
    indexOfOption(name).map(portRef)

  def field[E <: Data](name: String): Ref[E] = subRef(name).asInstanceOf[Ref[E]]

  def asRecord: InterfaceRecordView[T] = new InterfaceRecordView[T](this)

  transparent inline def selectDynamic(name: String):                                  Any = ${
    interfaceSelectDynamic('this, 'name)
  }
  transparent inline def applyDynamic(name: String)(inline args: Any*):                Any = ${
    interfaceApplyDynamic('this, 'name, 'args)
  }
  transparent inline def applyDynamicNamed(name: String)(inline args: (String, Any)*): Any = ${
    interfaceApplyDynamicNamed('this, 'name, 'args)
  }

final class InterfaceRecordView[T <: HWInterface[?]] private[zaozi] (
  private[zaozi] val interface: Interface[T]):
  def field[E <: Data](name: String): Ref[E] = interface.field[E](name)

  def getType: Record =
    val view = new Record {}
    view._elements ++= interface._tpe._elements
    view.instantiating = false
    view
