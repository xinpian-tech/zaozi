// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Operation, Type, Value, given}
import me.jiuyang.smtlib.tpe.*

import java.lang.foreign.Arena

trait ConstructorApi:
  // types
  def Array[T <: Data, U <: Data](
    domainType: T,
    rangeType:  U
  ): Array[T, U]

  def Bool: Bool

  def SInt: SInt

  def BitVector(
    isSigned: Boolean,
    width:    Int
  ): BitVector

  def SMTFunc[T <: Data, U <: Data](
    domainTypes: Seq[T],
    rangeType:   U
  ): SMTFunc[T, U]

  def Sort[T <: Data](
    identifier: String,
    sortParams: Seq[T]
  ): Sort[T]

  // values
  def smtValue[T <: Data](
    rangeType: T
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Ref[T]

  def smtValue[T <: Data](
    name:      String,
    rangeType: T
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name
  ): Ref[T]

  def smtFunc[T <: Data, U <: Data](
    domainTypes: Seq[T],
    rangeType:   U
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Ref[SMTFunc[T, U]]

  // smt functions
  def smtDistinct[T <: Data, D <: Referable[T]](
    values: Seq[D]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Ref[Bool]

  def smtAssert(
    value: Referable[Bool]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Ref[Bool]

  def smtReset(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit

  def smtSetLogic(
    logic: String
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit

  def smtEq[R <: Referable[?]](
    values: Seq[R]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Ref[Bool]

  def smtExists(
    weight:        BigInt,
    noPattern:     Boolean,
    boundVarNames: Seq[String]
  )(body:          (Arena, Context, Block) ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Ref[Bool]

  def smtForall(
    weight:        BigInt,
    noPattern:     Boolean,
    boundVarNames: Seq[String]
  )(body:          (Arena, Context, Block) ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Ref[Bool]

  def smtIte[T <: Data, COND <: Referable[Bool], THEN <: Referable[T], ELSE <: Referable[T]](
    cond:     COND
  )(thenBody: THEN
  )(elseBody: ELSE
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Ref[T]

  def smtPush(
    count: Int
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit

  def smtPop(
    count: Int
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit

  def smtCheck(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit

  def smtYield[T <: Data, R <: Referable[T]](
    values: Seq[R]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit

  def solver(
    body: (Arena, Context, Block) ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit

end ConstructorApi

trait TypeImpl:
  // value type
  extension (ref: Array[?, ?])
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type
  extension (ref: BitVector)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type
  extension (ref: Bool)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type
  extension (ref: SInt)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type
  extension (ref: SMTFunc[?, ?])
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type
  extension (ref: Sort[?])
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type
  extension (ref: Const[?])
    def operationImpl: Operation
    def referImpl(
      using Arena
    ):                 Value
  extension (ref: Ref[?])
    def operationImpl: Operation
    def referImpl(
      using Arena
    ):                 Value

// type operation trait

// bv type
trait Ashr[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def +>>(
      that: R | Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]
trait Lshr[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def >>(
      that: R | Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]
trait Shl[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def <<(
      that: R | Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]

// int type & bv type
trait Add[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def +(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]
trait Sub[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def -(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]
trait Mul[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def *(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]
trait Div[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def /(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]
trait Rem[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def %(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]
trait Lt[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def <(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]
trait Leq[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def <=(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]
trait Gt[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def >(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]
trait Geq[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def >=(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]
trait Eq[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def ===(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]
trait Neq[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def =/=(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]
trait And[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def &(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]
trait Or[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def |(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]

trait Xor[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def ^(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]

trait Concat[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def ++(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]

trait Extract[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def extract(
      lowBit: Int,
      tpe:    RET
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]

trait Repeat[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def repeat(
      tpe: RET
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]

trait Neg[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def unary_!(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]

trait Not[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def unary_~(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]

trait Implies[D <: Data, RET <: Data, R <: Referable[D]]:
  extension (ref: R)
    def ==>(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[RET]

// array type
trait Apply[T <: Data, U <: Data, R <: Referable[Array[T, U]]]:
  extension (ref: R)
    def apply(
      index: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[U]

trait Update[T <: Data, U <: Data, R <: Referable[Array[T, U]], S <: Referable[U]]:
  extension (ref: R)
    def update(
      index: Int,
      value: S
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Array[T, U]]

trait BroadCast[T <: Data, U <: Data, R <: Referable[Array[T, U]], S <: Referable[U]]:
  extension (ref: R)
    def broadcast(
      value: S
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Array[T, U]]

// func type
trait ApplyFunc[T <: Data, U <: Data, R <: Referable[SMTFunc[?, U]], S <: Referable[?]]:
  extension (ref: R)
    def apply(
      args: Seq[S]
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[U]

trait ArrayApi[T <: Data, U <: Data, R <: Referable[Array[T, U]], S <: Referable[U]]
    extends Apply[T, U, R]
    with Update[T, U, R, S]
    with BroadCast[T, U, R, S]

trait BitVectorApi[R <: Referable[BitVector]]
    extends Ashr[BitVector, BitVector, R]
    with Lshr[BitVector, BitVector, R]
    with Shl[BitVector, BitVector, R]
    with Add[BitVector, BitVector, R]
    with And[BitVector, BitVector, R]
    with Mul[BitVector, BitVector, R]
    with Neg[BitVector, BitVector, R]
    with Not[BitVector, BitVector, R]
    with Or[BitVector, BitVector, R]
    with Rem[BitVector, BitVector, R]
    with Div[BitVector, BitVector, R]
    with Xor[BitVector, BitVector, R]
    with Concat[BitVector, BitVector, R]
    with Extract[BitVector, BitVector, R]
    with Repeat[BitVector, BitVector, R]
    with Lt[BitVector, Bool, R]
    with Leq[BitVector, Bool, R]
    with Gt[BitVector, Bool, R]
    with Geq[BitVector, Bool, R]
    with Eq[BitVector, Bool, R]
    with Neq[BitVector, Bool, R]

trait SIntApi[R <: Referable[SInt]]
    extends Add[SInt, SInt, R]
    with Sub[SInt, SInt, R]
    with Mul[SInt, SInt, R]
    with Div[SInt, SInt, R]
    with Rem[SInt, SInt, R]
    with Lt[SInt, Bool, R]
    with Leq[SInt, Bool, R]
    with Gt[SInt, Bool, R]
    with Geq[SInt, Bool, R]
    with Eq[SInt, Bool, R]
    with Neq[SInt, Bool, R]

trait BoolApi[R <: Referable[Bool]]
    extends And[Bool, Bool, R]
    with Or[Bool, Bool, R]
    with Neg[Bool, Bool, R]
    with Xor[Bool, Bool, R]
    with Implies[Bool, Bool, R]
    with Eq[Bool, Bool, R]
    with Neq[Bool, Bool, R]

trait SMTFuncApi[T <: Data, U <: Data, R <: Referable[SMTFunc[?, U]], S <: Referable[?]] extends ApplyFunc[T, U, R, S]
