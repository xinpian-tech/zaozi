// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib.default

import org.llvm.mlir.scalalib.capi.dialect.smt.{given_TypeApi, TypeApi}
import org.llvm.mlir.scalalib.dialect.smt.operation.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, LocationApi, Operation, Type, Value, given}
import me.jiuyang.smtlib.*
import me.jiuyang.smtlib.tpe.*

import java.lang.foreign.Arena

// When Import the default, all method in ConstructorApi should be exported
val constructorApi = summon[ConstructorApi]
export constructorApi.*

given ConstructorApi with
  // types
  extension (value: Int)
    def S(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Const[SInt] =
      val constOp = summon[IntConstantApi].op(value, locate)
      constOp.operation.appendToBlock()
      new Const[SInt]:
        val _tpe:       SInt      = new Object with SInt
        val _operation: Operation = constOp.operation
    def B(
      isSigned: Boolean,
      width:    Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Const[BitVector] =
      val constOp = summon[BVConstantApi].op(value, width, locate)
      constOp.operation.appendToBlock()
      new Const[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned: Boolean = isSigned
          val _width:    Int     = width
        val _operation: Operation = constOp.operation

  extension (value: Boolean)
    def B(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Const[Bool] =
      val constOp = summon[BoolConstantApi].op(value, locate)
      constOp.operation.appendToBlock()
      new Const[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = constOp.operation

  def Array[T <: Data, U <: Data](
    domainType: T,
    rangeType:  U
  ): Array[T, U] = new Array[T, U]:
    private[smtlib] val _domainType = domainType
    private[smtlib] val _rangeType  = rangeType

  def BitVector(
    isSigned: Boolean,
    width:    Int
  ): BitVector = new BitVector:
    val _isSigned: Boolean = isSigned
    val _width:    Int     = width

  def Bool: Bool = new Object with Bool

  def SInt: SInt = new Object with SInt

  def SMTFunc[T <: Data, U <: Data](
    domainTypes: Seq[T],
    rangeType:   U
  ): SMTFunc[T, U] = new SMTFunc[T, U]:
    private[smtlib] val _domainTypes: Seq[T] = domainTypes
    private[smtlib] val _rangeType:   U      = rangeType

  def Sort[T <: Data](
    identifier: String,
    sortParams: Seq[T]
  ): Sort[T] = new Sort[T]:
    private[smtlib] val _identifier: String = identifier
    private[smtlib] val _sortParams: Seq[T] = sortParams

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
  ): Ref[T] =
    val op = summon[DeclareFunApi].op(summon[sourcecode.Name.Machine].value, locate, rangeType.toMlirType)
    op.operation.appendToBlock()
    new Ref[T]:
      val _tpe:       T         = rangeType
      val _operation: Operation = op.operation

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
  ): Ref[T] =
    val op = summon[DeclareFunApi].op(name, locate, rangeType.toMlirType)
    op.operation.appendToBlock()
    new Ref[T]:
      val _tpe:       T         = rangeType
      val _operation: Operation = op.operation

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
  ): Ref[SMTFunc[T, U]] =
    val tpe = SMTFunc(domainTypes, rangeType)
    val op  = summon[DeclareFunApi].op(summon[sourcecode.Name.Machine].value, locate, tpe.toMlirType)
    op.operation.appendToBlock()
    new Ref[SMTFunc[T, U]]:
      val _tpe:       SMTFunc[T, U] = tpe
      val _operation: Operation     = op.operation

  def smtDistinct[T <: Data, D <: Referable[T]](
    values: D*
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Ref[Bool] =
    val op = summon[DistinctApi].op(values.map(_.refer), locate)
    op.operation.appendToBlock()
    new Ref[Bool]:
      val _tpe: Bool = new Object with Bool
      val _operation = op.operation

  def smtAssert(
    value: Referable[Bool]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Ref[Bool] =
    val op = summon[AssertApi].op(value.refer, locate)
    op.operation.appendToBlock()
    new Ref[Bool]:
      val _tpe: Bool = new Object with Bool
      val _operation = op.operation

  def smtReset(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit =
    val op = summon[ResetApi].op(locate)
    op.operation.appendToBlock()

  def smtSetLogic(
    logic: String
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit =
    val op = summon[SetLogicApi].op(logic, locate)
    op.operation.appendToBlock()

  def smtEq[R <: Referable[?]](
    values: R*
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Ref[Bool] =
    val op = summon[EqApi].op(values.map(_.refer), locate)
    op.operation.appendToBlock()
    new Ref[Bool]:
      val _tpe: Bool = new Object with Bool
      val _operation = op.operation

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
  ): Ref[Bool] =
    val op = summon[ExistsApi].op(weight, noPattern, boundVarNames, locate)
    body(
      using summon[Arena],
      summon[Context],
      op.bodyBlock
    )
    op.operation.appendToBlock()
    new Ref[Bool]:
      val _tpe: Bool = new Object with Bool
      val _operation = op.operation

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
  ): Ref[Bool] =
    val op = summon[ForallApi].op(weight, noPattern, boundVarNames, locate)
    body(
      using summon[Arena],
      summon[Context],
      op.bodyBlock
    )
    op.operation.appendToBlock()
    new Ref[Bool]:
      val _tpe: Bool = new Object with Bool
      val _operation = op.operation

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
  ): Ref[T] =
    val op0 = summon[IteApi].op(cond.refer, thenBody.refer, elseBody.refer, locate)
    op0.operation.appendToBlock()
    new Ref[T]:
      val _tpe: T = thenBody._tpe
      val _operation = op0.operation

  def smtPush(
    count: Int
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit =
    val op = summon[PushApi].op(count, locate)
    op.operation.appendToBlock()

  def smtPop(
    count: Int
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit =
    val op = summon[PopApi].op(count, locate)
    op.operation.appendToBlock()

  def smtCheck(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit =
    val op = summon[CheckApi].op(locate)
    smtYield()(
      using summon[Arena],
      summon[Context],
      op.satBlock
    )
    smtYield()(
      using summon[Arena],
      summon[Context],
      op.unknownBlock
    )
    smtYield()(
      using summon[Arena],
      summon[Context],
      op.unsatBlock
    )
    op.operation.appendToBlock()

  def smtYield[T <: Data, R <: Referable[T]](
    values: R*
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit =
    val op = summon[YieldApi].op(values.map(_.refer), locate)
    op.operation.appendToBlock()

  def solver(
    body: (Arena, Context, Block) ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine
  ): Unit =
    val op = summon[SolverApi].op(locate)
    body(
      using summon[Arena],
      summon[Context],
      op.bodyBlock
    )
    op.operation.appendToBlock()

end given

given TypeImpl with
  extension (ref: Array[?, ?])
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      val mlirType = summon[TypeApi].getArray(ref._domainType.toMlirType, ref._rangeType.toMlirType)
      mlirType
  extension (ref: BitVector)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      val mlirType = summon[TypeApi].getBitVector(ref._width)
      mlirType
  extension (ref: Bool)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      val mlirType = summon[TypeApi].getBool
      mlirType
  extension (ref: SInt)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      val mlirType = summon[TypeApi].getInt
      mlirType
  extension (ref: SMTFunc[?, ?])
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      val mlirType = summon[TypeApi].getSMTFunc(ref._domainTypes.map(_.toMlirType), ref._rangeType.toMlirType)
      mlirType
  extension (ref: Sort[?])
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      val mlirType = summon[TypeApi].getSort(ref._identifier, ref._sortParams.map(_.toMlirType))
      mlirType

  // reference data
  extension (ref: Const[?])
    def operationImpl: Operation = ref._operation
    def referImpl(
      using Arena
    ): Value = ref.operation.getResult(0)
  extension (ref: Ref[?])
    def operationImpl: Operation = ref._operation
    def referImpl(
      using Arena
    ): Value = ref.operation.getResult(0)
end given

given [T <: Data, U <: Data, R <: Referable[Array[T, U]], S <: Referable[U]]: ArrayApi[T, U, R, S] with
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
    ): Ref[U] =
      val op = summon[ArraySelectApi].op(ref.refer, index.S.refer, locate)
      op.operation.appendToBlock()
      new Ref[U]:
        val _tpe:       U         = ref._tpe._rangeType
        val _operation: Operation = op.operation

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
    ): Ref[Array[T, U]] =
      val op = summon[ArrayStoreApi].op(ref.refer, index.S.refer, value.refer, locate)
      op.operation.appendToBlock()
      new Ref[Array[T, U]]:
        val _tpe:       Array[T, U] = new Array[T, U]:
          private[smtlib] val _domainType = ref._tpe._domainType
          private[smtlib] val _rangeType  = ref._tpe._rangeType
        val _operation: Operation   = op.operation

    def broadcast(
      value: S
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Array[T, U]] =
      val op = summon[ArrayBroadcastApi].op(value.refer, locate, ref._tpe.toMlirType)
      op.operation.appendToBlock()
      new Ref[Array[T, U]]:
        val _tpe:       Array[T, U] = new Array[T, U]:
          private[smtlib] val _domainType = ref._tpe._domainType
          private[smtlib] val _rangeType  = ref._tpe._rangeType
        val _operation: Operation   = op.operation
end given

given [R <: Referable[BitVector]]: BitVectorApi[R] with
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
    ): Ref[BitVector] =
      val shiftAmount = that match
        case that: Int                  => that.B(ref._tpe._isSigned, ref._tpe._width).refer
        case that: Referable[BitVector] => that.refer
      val op          = summon[BVAShrApi].op(ref.refer, shiftAmount, locate)
      op.operation.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = ref._tpe._width
        val _operation: Operation = op.operation

    def >>(
      that: R | Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[BitVector] =
      val shiftAmount = that match
        case that: Int                  => that.B(ref._tpe._isSigned, ref._tpe._width).refer
        case that: Referable[BitVector] => that.refer
      val op          = summon[BVLShrApi].op(ref.refer, shiftAmount, locate)
      op.operation.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = ref._tpe._width
        val _operation: Operation = op.operation

    def +(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[BitVector] =
      val op = summon[BVAddApi].op(ref.refer, that.refer, locate)
      op.operation.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = ref._tpe._width
        val _operation: Operation = op.operation

    def *(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[BitVector] =
      val op = summon[BVMulApi].op(ref.refer, that.refer, locate)
      op.operation.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = ref._tpe._width
        val _operation: Operation = op.operation

    def /(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[BitVector] =
      val op = ref._tpe._isSigned match
        case true  => summon[BVSDivApi].op(ref.refer, that.refer, locate).operation
        case false => summon[BVUDivApi].op(ref.refer, that.refer, locate).operation
      op.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = ref._tpe._width
        val _operation: Operation = op

    def %(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[BitVector] =
      val op = ref._tpe._isSigned match
        case true  => summon[BVSRemApi].op(ref.refer, that.refer, locate).operation
        case false => summon[BVURemApi].op(ref.refer, that.refer, locate).operation
      op.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = ref._tpe._width
        val _operation: Operation = op

    def <<(
      that: R | Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[BitVector] =
      val shiftAmount = that match
        case that: Int                  => that.B(ref._tpe._isSigned, ref._tpe._width).refer
        case that: Referable[BitVector] => that.refer
      val op          = summon[BVShlApi].op(ref.refer, shiftAmount, locate)
      op.operation.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = ref._tpe._width
        val _operation: Operation = op.operation

    def &(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[BitVector] =
      val op = summon[BVAndApi].op(ref.refer, that.refer, locate)
      op.operation.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = ref._tpe._width
        val _operation: Operation = op.operation

    def |(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[BitVector] =
      val op = summon[BVOrApi].op(ref.refer, that.refer, locate)
      op.operation.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = ref._tpe._width
        val _operation: Operation = op.operation

    def ^(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[BitVector] =
      val op = summon[BVXOrApi].op(ref.refer, that.refer, locate)
      op.operation.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = ref._tpe._width
        val _operation: Operation = op.operation

    def ++(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[BitVector] =
      val op = summon[ConcatApi].op(ref.refer, that.refer, locate)
      op.operation.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = ref._tpe._width + that._tpe._width
        val _operation: Operation = op.operation

    def extract(
      lowBit: Int,
      tpe:    BitVector
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[BitVector] =
      val op = summon[ExtractApi].op(lowBit, ref.refer, locate, tpe.toMlirType)
      op.operation.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = tpe._width
        val _operation: Operation = op.operation

    def repeat(
      tpe: BitVector
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[BitVector] =
      val op = summon[RepeatApi].op(ref.refer, locate, tpe.toMlirType)
      op.operation.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = tpe._width
        val _operation: Operation = op.operation

    def unary_~(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[BitVector] =
      val op = summon[BVNegApi].op(ref.refer, locate)
      op.operation.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = ref._tpe._width
        val _operation: Operation = op.operation

    def unary_!(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[BitVector] =
      val op = summon[BVNotApi].op(ref.refer, locate)
      op.operation.appendToBlock()
      new Ref[BitVector]:
        val _tpe:       BitVector = new BitVector:
          val _isSigned = ref._tpe._isSigned
          val _width    = ref._tpe._width
        val _operation: Operation = op.operation

    def <(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[BVCmpApi].op(ref.refer, that.refer, "slt", locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def <=(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[BVCmpApi].op(ref.refer, that.refer, "sle", locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def >(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[BVCmpApi].op(ref.refer, that.refer, "sgt", locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def >=(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[BVCmpApi].op(ref.refer, that.refer, "sge", locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def ===(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[EqApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def =/=(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[DistinctApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation
end given

given [R <: Referable[Bool]]: BoolApi[R] with
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
    ): Ref[Bool] =
      val op = summon[AndApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def |(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[OrApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def unary_!(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[NotApi].op(ref.refer, locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def ^(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[XOrApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def ==>(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[ImpliesApi].op(ref.refer, that.refer, locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def ===(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[EqApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def =/=(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[DistinctApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation
end given

given [R <: Referable[SInt]]: SIntApi[R] with
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
    ): Ref[SInt] =
      val op = summon[IntAddApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Ref[SInt]:
        val _tpe:       SInt      = new Object with SInt
        val _operation: Operation = op.operation
    def -(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[SInt] =
      val op = summon[IntSubApi].op(ref.refer, that.refer, locate)
      op.operation.appendToBlock()
      new Ref[SInt]:
        val _tpe:       SInt      = new Object with SInt
        val _operation: Operation = op.operation

    def *(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[SInt] =
      val op = summon[IntMulApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Ref[SInt]:
        val _tpe:       SInt      = new Object with SInt
        val _operation: Operation = op.operation

    def /(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[SInt] =
      val op = summon[IntDivApi].op(ref.refer, that.refer, locate)
      op.operation.appendToBlock()
      new Ref[SInt]:
        val _tpe:       SInt      = new Object with SInt
        val _operation: Operation = op.operation

    def %(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[SInt] =
      val op = summon[IntModApi].op(ref.refer, that.refer, locate)
      op.operation.appendToBlock()
      new Ref[SInt]:
        val _tpe:       SInt      = new Object with SInt
        val _operation: Operation = op.operation

    def <(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[IntCmpApi].op(ref.refer, that.refer, "lt", locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def <=(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[IntCmpApi].op(ref.refer, that.refer, "le", locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def >(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[IntCmpApi].op(ref.refer, that.refer, "gt", locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def >=(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[IntCmpApi].op(ref.refer, that.refer, "ge", locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def ===(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[EqApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

    def =/=(
      that: R
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[Bool] =
      val op = summon[DistinctApi].op(Seq(ref.refer, that.refer), locate)
      op.operation.appendToBlock()
      new Ref[Bool]:
        val _tpe:       Bool      = new Object with Bool
        val _operation: Operation = op.operation

end given

given [T <: Data, U <: Data, R <: Referable[SMTFunc[?, U]], S <: Referable[?]]: SMTFuncApi[T, U, R, S] with
  extension (ref: R)
    def apply(
      args: S*
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    ): Ref[U] =
      val op = summon[ApplyFuncApi].op(ref.refer, args.map(_.refer), locate)
      op.operation.appendToBlock()
      new Ref[U]:
        val _tpe:       U         = ref._tpe._rangeType
        val _operation: Operation = op.operation

end given

private inline def locate(
  using Arena,
  Context,
  sourcecode.File,
  sourcecode.Line
): Location =
  summon[LocationApi].locationFileLineColGet(
    summon[sourcecode.File].value,
    summon[sourcecode.Line].value,
    0
  )
