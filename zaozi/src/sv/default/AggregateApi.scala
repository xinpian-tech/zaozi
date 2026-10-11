// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.default

import me.jiuyang.zaozi.default.locate
import me.jiuyang.zaozi.sv.{
  AggregateApi,
  ConstExpr,
  InterfaceSignal,
  InterfaceSignalRef,
  Modport,
  TypeImpl,
  VerilogInterface
}
import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.*
import org.llvm.circt.scalalib.capi.dialect.hw.given_TypeApi as HWType
import org.llvm.circt.scalalib.dialect.hw.operation.{
  ArrayGetApi,
  StructCreateApi,
  StructExtractApi,
  UnionCreateApi,
  UnionExtractApi,
  given
}
import org.llvm.circt.scalalib.dialect.sv.operation.{ArrayIndexInOutApi, StructFieldInOutApi, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import java.lang.foreign.Arena

private def canonical(tpe: Data): Data = tpe match
  case alias: TypeAlias[?] => canonical(alias._inner)
  case other => other

private def element(tpe: Data): Data = canonical(tpe) match
  case array: PackedArray[?]   => array._element
  case array: UnpackedArray[?] => array._element
  case _ => throw new IllegalArgumentException("Indexing requires an array")

private def refOf[T <: Data](tpe: T, value: Value, bindings: Map[String, ConstExpr] = Map.empty):  Ref[T]  =
  new Ref[T]:
    override private[zaozi] val _bindings = bindings
    private[zaozi] val _tpe               = tpe
    private[zaozi] val _refer             = value
private def nodeOf[T <: Data](tpe: T, value: Value, bindings: Map[String, ConstExpr] = Map.empty): Node[T] =
  new Node[T]:
    override private[zaozi] val _bindings = bindings
    private[zaozi] val _tpe               = tpe
    private[zaozi] val _refer             = value

given AggregateApi with
  def StructValue[T <: Data](
    tpe:    T,
    fields: Seq[FieldBinding]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[T] =
    val structure = canonical(tpe).asInstanceOf[Struct]
    val values    = fields.map(f => f.name -> f.value).toMap
    require(
      values.size == fields.size && values.keySet == structure._fields.map(_.name).toSet,
      "StructValue requires exactly one binding for each field"
    )
    val op        = summon[StructCreateApi].op(structure._fields.map(f => values(f.name).refer).toSeq, tpe.toMlirType, locate)
    op.operation.appendToBlock()
    nodeOf(tpe, op.result)

  def UnionValue[T <: Data, E <: Data](
    tpe:   T,
    field: Field[E],
    value: Referable[E]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[T] =
    val union = canonical(tpe).asInstanceOf[Union]
    val op    =
      summon[UnionCreateApi].op(value.refer, union._fields.indexWhere(_.name == field.name), tpe.toMlirType, locate)
    op.operation.appendToBlock()
    nodeOf(tpe, op.result)

  def fieldValue[T <: Data, E <: Data](
    ref:  Referable[T],
    name: String
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[E] =
    val aggregate = canonical(ref.getType).asInstanceOf[Aggregate]
    val index     = aggregate._fields.indexWhere(_.name == name)
    val tpe       = aggregate._fields(index).dataType.asInstanceOf[E]
    val op        = aggregate match
      case _: Struct =>
        summon[StructExtractApi]
          .op(
            ref.refer,
            index,
            tpe.toMlirType(
              using summon[Arena],
              summon[Context],
              new SVTypeImpl(ref._bindings)
            ),
            locate
          )
          .operation
      case _: Union  =>
        summon[UnionExtractApi]
          .op(
            ref.refer,
            index,
            tpe.toMlirType(
              using summon[Arena],
              summon[Context],
              new SVTypeImpl(ref._bindings)
            ),
            locate
          )
          .operation
    op.appendToBlock()
    nodeOf(tpe, op.getResult(0), ref._bindings)

  def fieldRef[T <: Data, E <: Data](
    ref:  Ref[T],
    name: String
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Ref[E] =
    canonical(ref.getType) match
      case structure: Struct if HWType.isInOut(ref._refer.getType)() =>
        val tpe = structure._fields.find(_.name == name).get.dataType.asInstanceOf[E]
        val op  = summon[StructFieldInOutApi].op(ref._refer, name, locate)
        op.operation.appendToBlock()
        refOf(tpe, op.result, ref._bindings)
      case _ =>
        val value = fieldValue[T, E](ref, name)
        refOf(value.getType, value._refer, ref._bindings)

  def indexRef[T <: Data, E <: Data](
    ref:   Ref[T],
    index: Referable[Bits]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Ref[E] =
    if HWType.isInOut(ref._refer.getType)() then
      val tpe = element(ref.getType).asInstanceOf[E]
      val op  = summon[ArrayIndexInOutApi].op(
        ref._refer,
        index.refer,
        HWType.inOutTypeGet(
          tpe.toMlirType(
            using summon[Arena],
            summon[Context],
            new SVTypeImpl(ref._bindings)
          )
        )(),
        locate
      )
      op.operation.appendToBlock()
      refOf(tpe, op.result, ref._bindings)
    else
      val value = indexValue[T, E](ref, index)
      refOf(value.getType, value._refer, ref._bindings)

  def indexValue[T <: Data, E <: Data](
    ref:   Referable[T],
    index: Referable[Bits]
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[E] =
    require(
      canonical(ref.getType).isInstanceOf[PackedArray[?]],
      "Assign an unpacked array value to a Variable before indexing"
    )
    val tpe = element(ref.getType).asInstanceOf[E]
    val op  = summon[ArrayGetApi].op(
      ref.refer,
      index.refer,
      tpe.toMlirType(
        using summon[Arena],
        summon[Context],
        new SVTypeImpl(ref._bindings)
      ),
      locate
    )
    op.operation.appendToBlock()
    nodeOf(tpe, op.result, ref._bindings)

  def interfaceField[T <: Data, E <: Data](
    ref:  Referable[T],
    name: String
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): InterfaceSignalRef[E] =
    val (definition, modport) = ref.getType match
      case port:      Modport[?]       => (port.interface, Some(port))
      case interface: VerilogInterface => (interface, None)
      case _ => throw new IllegalArgumentException("Expected an SV interface reference")
    val field                 = definition.signals.find(_.name == name).get.asInstanceOf[InterfaceSignal[E]]
    val handle                = ref.refer
    if modport.nonEmpty && handle.isOpResult && handle.opResultGetOwner.getName.str == "sv.modport.get" then
      new InterfaceSignalRef(handle.opResultGetOwner.getOperand(0), field)
    else new InterfaceSignalRef(handle, field, modport)

  def interfaceModport[T <: Data, I <: VerilogInterface](
    ref:  Referable[T],
    name: String
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Node[Modport[I]] =
    val port = ref.getType.asInstanceOf[VerilogInterface].modports.find(_.name == name).get.asInstanceOf[Modport[I]]
    import org.llvm.circt.scalalib.dialect.sv.operation.given
    emitted(
      port,
      summon[org.llvm.circt.scalalib.dialect.sv.operation.GetModportApi]
        .op(ref.refer, name, port.toMlirType, locate)
        .operation
    )
