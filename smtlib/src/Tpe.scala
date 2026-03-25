// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib.tpe

import me.jiuyang.smtlib.TypeImpl
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, Operation, Type, Value}

import java.lang.foreign.Arena
import scala.language.dynamics

trait Data:
  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type

trait Array[T <: Data, U <: Data] extends Data:
  private[smtlib] val _domainType: T
  private[smtlib] val _rangeType:  U

  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl

trait BitVector extends Data:
  private[smtlib] val _isSigned: Boolean
  private[smtlib] val _width:    Int

  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl

trait Bool extends Data:
  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl

trait SInt extends Data:
  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl

trait SMTFunc[+T <: Data, U <: Data] extends Data:
  private[smtlib] val _domainTypes: Seq[T]
  private[smtlib] val _rangeType:   U

  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl

trait Sort[+T <: Data] extends Data:
  private[smtlib] val _identifier: String
  private[smtlib] val _sortParams: Seq[T]

  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl

trait Referable[T <: Data] extends Dynamic:
  private[smtlib] val _tpe: T

  def refer(
    using Arena,
    TypeImpl
  ): Value

trait HasOperation:
  private[smtlib] val _operation: Operation

  def operation(
    using TypeImpl
  ): Operation

trait Const[T <: Data] extends Referable[T] with HasOperation:
  private[smtlib] val _tpe:       T
  private[smtlib] val _operation: Operation

  def operation(
    using TypeImpl
  ): Operation = this.operationImpl

  def refer(
    using Arena,
    TypeImpl
  ): Value = this.referImpl

/** Marker for "solver picks this register freely". Never evaluated — used as a sentinel in constraint generators to
  * skip the equality constraint and only apply the range constraint.
  */
object FreeReg extends Referable[SInt]:
  private[smtlib] val _tpe: SInt = new Object with SInt
  def refer(
    using Arena,
    TypeImpl
  ): Value = throw UnsupportedOperationException("FreeReg is a placeholder — do not evaluate")

trait Ref[T <: Data] extends Referable[T] with HasOperation:
  private[smtlib] val _tpe:       T
  private[smtlib] val _operation: Operation

  def operation(
    using TypeImpl
  ): Operation = this.operationImpl

  def refer(
    using Arena,
    TypeImpl
  ): Value = this.referImpl
