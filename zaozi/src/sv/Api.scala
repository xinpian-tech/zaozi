// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv

import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.*
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Block, Context, Type, Value}
import java.lang.foreign.Arena

/** Shared declarations, available before ports and architecture are materialized. */
final class TypeScopeContext private[sv] (private[sv] val name: String):
  private[sv] val declarations = collection.mutable.ArrayBuffer.empty[Data]
  private[sv] def use(tpe: Data): Unit =
    tpe match
      case alias: TypeAlias[?] =>
        use(alias._inner)
        if !declarations.contains(alias) then declarations += alias
      case _ => ()
    tpe match
      case aggregate: Aggregate            => aggregate._fields.foreach(f => use(f.dataType))
      case array:     PackedArray[?]       => use(array._element)
      case array:     UnpackedArray[?]     => use(array._element)
      case array:     UnpackedOpenArray[?] => use(array._element)
      case _ => ()
    if tpe.isInstanceOf[NamedType] && !declarations.contains(tpe) then declarations += tpe

trait TypeImpl:
  extension [T <: Data](ref: Referable[T])
    def valueTypeImpl(
      using Arena,
      Context
    ): Type
  extension (tpe:            Data)
    def materializeTypeImpl(
      using Arena,
      Context
    ): Type
  extension (tpe:            SVString.type)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type
  extension [T <: Data](tpe: UnpackedOpenArray[T])
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type
  extension (tpe:            VerilogInterface)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type
  extension (tpe:            Modport[?])
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type

  extension (tpe:            Struct)
    def toMlirTypeImpl(
      using Arena,
      Context
    ):            Type
  extension (tpe:            Union)
    def toMlirTypeImpl(
      using Arena,
      Context
    ):            Type
  extension (tpe:            Bits)
    def toMlirTypeImpl(
      using Arena,
      Context
    ):            Type
  extension [T <: Data](tpe: PackedArray[T])
    def toMlirTypeImpl(
      using Arena,
      Context
    ):            Type
  extension [T <: Data](tpe: UnpackedArray[T])
    def toMlirTypeImpl(
      using Arena,
      Context
    ):            Type
  extension (tpe:            Enum)
    def toMlirTypeImpl(
      using Arena,
      Context
    ):            Type
  extension [T <: Data](tpe: TypeAlias[T])
    def toMlirTypeImpl(
      using Arena,
      Context
    ):            Type
  extension (expr:           ConstExpr)
    def toAttributeImpl(
      using Arena,
      Context
    ):            Attribute
  extension (parameter:      ParameterField)
    def declarationImpl(
      using Arena,
      Context
    ):            Attribute
  extension [T <: Data](ref: Referable[T])
    def referImpl(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ):            Value

trait ConstructorApi:
  def UnpackedOpenArray[T <: Data](element: T):                     me.jiuyang.zaozi.sv.valuetpe.UnpackedOpenArray[T]
  extension (value:                         BigInt)
    def B(
      width: Int
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ):                                                              Const[Bits]
  def Bits(width:                           Int):                   Bits
  def Bits(width:                           ConstExpr):             Bits
  def PackedArray[T <: Data](size:          Int, element:       T): PackedArray[T]
  def PackedArray[T <: Data](size:          ConstExpr, element: T): PackedArray[T]
  def UnpackedArray[T <: Data](size:        Int, element:       T): UnpackedArray[T]
  def UnpackedArray[T <: Data](size:        ConstExpr, element: T): UnpackedArray[T]
  def TypeScope[T](
    body: (Block, TypeScopeContext) ?=> T
  )(
    using Arena,
    Context,
    Block,
    InstanceContext,
    sourcecode.Name.Machine,
    sourcecode.File,
    sourcecode.Line
  ):                                                                T
  def TypeDef[T <: Data](
    tpe: T
  )(
    using TypeScopeContext,
    sourcecode.Name.Machine
  ):                                                                TypeAlias[T]

  /** Declare a static SystemVerilog variable; this carries no clock or inferred register semantics. */
  def Variable[T <: Data](
    tpe: T
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    InstanceContext,
    sourcecode.Name.Machine,
    sourcecode.File,
    sourcecode.Line
  ): Ref[T]
  def LocalParam(
    expr: ConstExpr
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    InstanceContext,
    sourcecode.Name.Machine,
    sourcecode.File,
    sourcecode.Line
  ): Const[Bits]
  def ParameterValue(
    expr: ConstExpr
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Const[Bits]

trait StatementApi:
  def alwaysFF(
    clock: Referable[Bits],
    edge:  org.llvm.circt.scalalib.dialect.sv.attr.EventControl =
      org.llvm.circt.scalalib.dialect.sv.attr.EventControl.PosEdge
  )(body:  Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Unit
  def alwaysComb(
    body: Block ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): Unit
  extension [T <: Data](dest: Ref[T])
    /** Continuous at module scope; blocking inside a procedural region. */
    def :=(
      src: Referable[T]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Unit
    def :==(
      src: Referable[T]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Unit

trait ExpressionApi:
  extension (lhs:                              Referable[Bits])
    def +(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits]
    def -(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits]
    def *(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits]
    def &(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits]
    def |(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits]
    def ^(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits]
    def ===(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits]
    def =/=(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits]
    def <(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits]
    def <=(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits]
    def >(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits]
    def >=(
      rhs: Referable[Bits]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits]
  extension [T <: Enum | TypeAlias[Enum]](lhs: Referable[T])
    @scala.annotation.targetName("enumEqual")
    def ===(
      rhs: Referable[T]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[Bits]
  extension (cond:                             Referable[Bits])
    def ?[T <: Data](
      whenTrue:  Referable[T],
      whenFalse: Referable[T]
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ): Node[T]

/** Queries on materialized references, not cached declaration dimensions. */
trait TypeQueryApi:
  extension [T <: Data](ref: Referable[T])
    /** Bit width of a concrete native value type. Symbolic/unsized types have no integer width. */
    def width(
      using Arena,
      Context,
      TypeImpl
    ): Int
  extension [T <: Data](ref: Referable[PackedArray[T]])
    /** Number of elements after instance parameter substitution. */
    def count(
      using Arena,
      Context,
      TypeImpl
    ): Int
