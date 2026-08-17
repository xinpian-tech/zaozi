// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.reftpe

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.zaozi.magic.DynamicSubfield
import me.jiuyang.zaozi.magic.macros.{referableApplyDynamic, referableApplyDynamicNamed, referableSelectDynamic}
import org.llvm.circt.scalalib.dialect.firrtl.operation.Module as CirctModule
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Operation, Type, Value, given}

import java.lang.foreign.Arena
import scala.language.dynamics

// TODO: consider propagating Const through arithmetic operators (e.g. Const[UInt] + Const[UInt] => Const[UInt])
type Propagated[R <: Referable[?], RET <: Data] = R match
  case Const[?] => Const[RET]
  case _        => Node[RET]

trait Referable[T <: Data] extends Dynamic:
  private[zaozi] val _tpe:   T
  private[zaozi] val _refer: Value

  // Ideally, we can get all attribute from MLIR but the Scala type itself
  def getType = _tpe

  def refer(
    using Arena,
    TypeImpl
  ): Value

  def definingOp(
    using Arena
  ): Option[Operation] =
    if _refer.isOpResult then Some(_refer.opResultGetOwner) else None

  def width(
    using Arena,
    Context,
    TypeImpl
  ): Int = _tpe.width

  private[zaozi] def subRef(
    name: String
  )(
    using Arena,
    Block,
    Context,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Ref[Data] =
    _tpe.asInstanceOf[DynamicSubfield].getRefViaFieldValName[Data](refer, name)

  private[zaozi] def subRefOption(
    name: String
  )(
    using Arena,
    Block,
    Context,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Option[Ref[Data]] =
    _tpe.asInstanceOf[DynamicSubfield].getOptionRefViaFieldValName[Data](refer, name)

  /** Select a subfield by a *runtime* name, typed as `T`. Unlike `selectDynamic`, which needs
    * the name at compile time, this takes a `String` value — for generic code iterating over a
    * bundle's fields (e.g. a contract's ports).
    */
  def subfield[T <: Data](
    name: String
  )(
    using Arena,
    Block,
    Context,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Ref[T] =
    subRef(name).asInstanceOf[Ref[T]]

  transparent inline def selectDynamic(name: String):                                  Any = ${ referableSelectDynamic('this, 'name) }
  transparent inline def applyDynamic(name: String)(inline args: Any*):                Any = ${
    referableApplyDynamic('this, 'name, 'args)
  }
  transparent inline def applyDynamicNamed(name: String)(inline args: (String, Any)*): Any = ${
    referableApplyDynamicNamed('this, 'name, 'args)
  }

trait Writable[T <: Data] extends Referable[T]
