// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.reftpe

import me.jiuyang.zaozi.sv.TypeImpl
import me.jiuyang.zaozi.sv.valuetpe.Data
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Type, Value, given}
import java.lang.foreign.Arena

trait Readable[T <: Data]
trait Writable[T <: Data] extends Readable[T]

trait Referable[T <: Data] extends Readable[T]:
  private[zaozi] def _bindings: Map[String, me.jiuyang.zaozi.sv.ConstExpr] = Map.empty
  private[zaozi] val _tpe: T

  /** Original declaration identity; its dimensions may still be symbolic after instantiation. */
  def getType: T = _tpe

  /** Reading an SV handle materializes sv.read_inout at its use site. */
  def refer(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Value = this.referImpl

  /** Actual native value type, including instance parameter substitution. */
  def mlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.valueTypeImpl

private[zaozi] trait ValueReferable[T <: Data] extends Referable[T]:
  private[zaozi] val _refer: Value
