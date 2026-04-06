// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.reftpe

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.zaozi.magic.macros.{referableApplyDynamic, referableApplyDynamicNamed, referableSelectDynamic}
import org.llvm.circt.scalalib.dialect.firrtl.operation.Module as CirctModule
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Operation, Type, Value}

import java.lang.foreign.Arena
import scala.language.dynamics

// TODO: consider propagating Const through arithmetic operators (e.g. Const[UInt] + Const[UInt] => Const[UInt])
type Propagated[R <: Referable[?], RET <: Data] = R match
  case Const[?] => Const[RET]
  case _        => Node[RET]

trait Referable[T <: Data] extends Dynamic:
  private[zaozi] val _tpe: T

  // Ideally, we can get all attribute from MLIR but the Scala type itself
  def getType = _tpe

  def refer(
    using Arena,
    TypeImpl
  ): Value

  def width(
    using Arena,
    Context,
    TypeImpl
  ): Int = _tpe.width

  /** macro to call [[DynamicSubfield.getRefViaFieldValName]] */
  transparent inline def selectDynamic(name: String):                                  Any = ${ referableSelectDynamic('this, 'name) }
  transparent inline def applyDynamic(name: String)(inline args: Any*):                Any = ${
    referableApplyDynamic('this, 'name, 'args)
  }
  transparent inline def applyDynamicNamed(name: String)(inline args: (String, Any)*): Any = ${
    referableApplyDynamicNamed('this, 'name, 'args)
  }

trait Writable[T <: Data] extends Referable[T]

trait HasOperation:
  def operation(
    using TypeImpl
  ): Operation
