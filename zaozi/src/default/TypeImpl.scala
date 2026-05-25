// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.given
import org.llvm.circt.scalalib.capi.dialect.firrtl.FirrtlBundleFieldApi
import org.llvm.circt.scalalib.dialect.firrtl.operation.{OpenSubfieldApi, SubfieldApi, given}
import org.llvm.circt.scalalib.capi.dialect.firrtl.TypeApi as FirrtlTypeApi
import org.llvm.circt.scalalib.capi.dialect.ltl.{given_TypeApi, TypeApi as LTLTypeApi}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, LocationApi, Operation, Type, Value, given}

import java.lang.foreign.Arena
import scala.collection.immutable.SeqMap

given TypeImpl with
  private def buildBundleMlirType(
    ref: Aggregate
  )(
    using Arena,
    Context
  ): Type =
    ref.instantiating = false
    ref._elements
      .map(f =>
        summon[FirrtlBundleFieldApi]
          .createFirrtlBundleField(f.name, f.isFlipped, f.dataType.toMlirType)
      )
      .toSeq // to immutable Seq
      .getBundle

  extension (ref: Interface[?])
    def operationImpl: Operation = ref._operation
    def referImpl(
      using Arena
    ): Value = ref.operation.getResult(0)
  extension (ref: Wire[?])
    def operationImpl: Operation = ref._operation
    def referImpl(
      using Arena
    ): Value = ref.operation.getResult(0)
  extension (ref: Reg[?])
    def operationImpl: Operation = ref._operation
    def referImpl(
      using Arena
    ): Value = ref.operation.getResult(0)
  extension (ref: Node[?])
    def operationImpl: Operation = ref._operation
    def referImpl(
      using Arena
    ): Value = ref.operation.getResult(0)
  extension (ref: Ref[?])
    def operationImpl: Operation = ref._operation
    def referImpl(
      using Arena
    ): Value = ref.operation.getResult(0)
  extension (ref: Const[?])
    def operationImpl: Operation = ref._operation
    def referImpl(
      using Arena
    ): Value = ref.operation.getResult(0)
  extension (ref: Instance[?, ?])
    def operationImpl:        Operation = ref._operation
    def ioImpl[T <: Data]:    Wire[T]   = ref._ioWire.asInstanceOf[Wire[T]]
    def probeImpl[T <: Data]: Wire[T]   = ref._probeWire.asInstanceOf[Wire[T]]
  extension (ref: Sequence)
    def operationImpl: Operation = ref._operation
    def referImpl(
      using Arena
    ): Value = ref.operation.getResult(0)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type = summon[LTLTypeApi].sequenceTypeGet
  extension (ref: Property)
    def operationImpl: Operation = ref._operation
    def referImpl(
      using Arena
    ): Value = ref.operation.getResult(0)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type = summon[LTLTypeApi].propertyTypeGet
  extension (ref: Immediate)
    def operationImpl: Operation = ref._operation
    def referImpl(
      using Arena
    ): Value = ref.operation.getResult(0)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type = 1.integerTypeGet

  extension (ref: Reset)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      val mlirType =
        if (ref._isAsync)
          summon[FirrtlTypeApi].getAsyncReset
        else
          1.getUInt
      mlirType
  extension (ref: Clock)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      val mlirType = summon[FirrtlTypeApi].getClock
      mlirType
  extension (ref: UInt)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      val mlirType = ref._width.getUInt
      mlirType
  extension (ref: SInt)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      val mlirType = ref._width.getSInt
      mlirType
  extension (ref: Bits)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      val mlirType = ref._width.getUInt
      mlirType
  extension (ref: Analog)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      val mlirType = ref._width.getAnalog
      mlirType
  extension (ref: Bool)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      val mlirType = 1.getUInt
      mlirType
  extension (ref: ProbeBundle)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type = buildBundleMlirType(ref)
    def ReadProbeImpl[T <: Data & CanProbe](
      tpe:   T,
      layer: LayerTree
    )(
      using sourcecode.Name.Machine
    ): BundleField[RProbe[T]] =
      require(ref.instantiating)
      val rProbe: RProbe[T] = new RProbe[T]:
        val _baseType: T         = tpe
        val _color:    LayerTree = layer
      val bf = BundleField[RProbe[T]](name = bundleFieldName, isFlipped = false, dataType = rProbe)
      ref._elements += bf
      bf
    def ReadWriteProbeImpl[T <: Data & CanProbe](
      tpe:   T,
      layer: LayerTree
    )(
      using sourcecode.Name.Machine
    ): BundleField[RWProbe[T]] =
      require(ref.instantiating)
      val rwProbe: RWProbe[T] = new RWProbe[T]:
        val _baseType: T         = tpe
        val _color:    LayerTree = layer
      val bf = BundleField[RWProbe[T]](name = bundleFieldName, isFlipped = false, dataType = rwProbe)
      ref._elements += bf
      bf

  extension (ref: Bundle)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type = buildBundleMlirType(ref)
    def FlippedImpl[T <: Data](
      tpe: T
    )(
      using sourcecode.Name.Machine
    ): BundleField[T] =
      require(ref.instantiating)
      val bf = BundleField[T](name = bundleFieldName, isFlipped = true, dataType = tpe)
      ref._elements += bf
      bf

    def AlignedImpl[T <: Data](
      tpe: T
    )(
      using sourcecode.Name.Machine
    ): BundleField[T] =
      require(ref.instantiating)
      val bf = BundleField[T](name = bundleFieldName, isFlipped = false, dataType = tpe)
      ref._elements += bf
      bf

  extension (ref: ProbeRecord)
    def elements: Seq[BundleField[?]] =
      require(!ref.instantiating)
      ref._elements.toSeq
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type = buildBundleMlirType(ref)
    def ReadProbeImpl[T <: Data & CanProbe](
      name:  String,
      tpe:   T,
      layer: LayerTree
    ): BundleField[RProbe[T]] =
      require(ref.instantiating)
      val rProbe: RProbe[T] = new RProbe[T]:
        val _baseType: T         = tpe
        val _color:    LayerTree = layer
      val bf = BundleField[RProbe[T]](name = name, isFlipped = false, dataType = rProbe)
      ref._elements += bf
      bf
    def ReadWriteProbeImpl[T <: Data & CanProbe](
      name:  String,
      tpe:   T,
      layer: LayerTree
    ): BundleField[RWProbe[T]] =
      require(ref.instantiating)
      val rwProbe: RWProbe[T] = new RWProbe[T]:
        val _baseType: T         = tpe
        val _color:    LayerTree = layer
      val bf = BundleField[RWProbe[T]](name = name, isFlipped = false, dataType = rwProbe)
      ref._elements += bf
      bf

  extension (ref: Record)
    def elements: Seq[BundleField[?]] =
      require(!ref.instantiating)
      ref._elements.toSeq
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type = buildBundleMlirType(ref)
    def FlippedImpl[T <: Data](
      name: String,
      tpe:  T
    ): BundleField[T] =
      require(ref.instantiating)
      val bf = BundleField[T](name = name, isFlipped = true, dataType = tpe)
      ref._elements += bf
      bf

    def AlignedImpl[T <: Data](
      name: String,
      tpe:  T
    ): BundleField[T] =
      require(ref.instantiating)
      val bf = BundleField[T](name = name, isFlipped = false, dataType = tpe)
      ref._elements += bf
      bf

  extension (ref: RProbe[?])
    def toMlirTypeImpl(
      using Arena,
      Context,
      TypeImpl
    ): Type =
      ref._baseType.toMlirType.getRef(false, ref._color.nameHierarchy)

  extension (ref: RWProbe[?])
    def toMlirTypeImpl(
      using Arena,
      Context,
      TypeImpl
    ): Type =
      ref._baseType.toMlirType.getRef(true, ref._color.nameHierarchy)

  extension (data: Data)
    def widthImpl(
      using Arena,
      Context
    ): Int =
      data.toMlirType.getBitWidth(true).toInt
  extension (ref:  Vec[?])
    def countImpl(
      using Arena,
      Context
    ): Int = ref.toMlirType.getVectorElementNum.toInt
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      val mlirType = ref._elementType.toMlirType.getVector(ref._count)
      mlirType
  extension (ref:  ProbeBundle)
    def getRefViaFieldValNameImpl[E <: Data](
      refer:        Value,
      fieldValName: String
    )(
      using Arena,
      Block,
      Context,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    )(
      using TypeImpl
    ): Ref[E] = getOptionRefViaFieldValNameImpl(refer, fieldValName).getOrElse:
      throw new Exception(s"$fieldValName not found in ${ref._elements.map(_.name)}")
    def getOptionRefViaFieldValNameImpl[E <: Data](
      refer:        Value,
      fieldValName: String
    )(
      using Arena,
      Block,
      Context,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    )(
      using TypeImpl
    ): Option[Ref[E]] =
      require(!ref.instantiating)
      ref._elements
        .find(_.name == fieldValName)
        .map: field =>
          val openSubfieldOp = summon[OpenSubfieldApi]
            .op(
              input = refer,
              fieldIndex = refer.getType.getBundleFieldIndex(field.name),
              location = locate
            )
          openSubfieldOp.operation.appendToBlock()
          new Ref[E]:
            val _tpe:       E         = field.dataType.asInstanceOf[E]
            val _operation: Operation = openSubfieldOp.operation

  extension (ref: Bundle)
    def getRefViaFieldValNameImpl[E <: Data](
      refer:        Value,
      fieldValName: String
    )(
      using Arena,
      Block,
      Context,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    )(
      using TypeImpl
    ): Ref[E] = getOptionRefViaFieldValNameImpl(refer, fieldValName).getOrElse:
      throw new Exception(s"$fieldValName not found in ${ref._elements.map(_.name)}")
    def getOptionRefViaFieldValNameImpl[E <: Data](
      refer:        Value,
      fieldValName: String
    )(
      using Arena,
      Block,
      Context,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    )(
      using TypeImpl
    ): Option[Ref[E]] =
      require(!ref.instantiating)
      ref._elements
        .find(_.name == fieldValName)
        .map: field =>
          val subfieldOp = summon[SubfieldApi]
            .op(
              input = refer,
              fieldIndex = refer.getType.getBundleFieldIndex(field.name),
              location = locate
            )
          subfieldOp.operation.appendToBlock()
          new Ref[E]:
            val _tpe:       E         = field.dataType.asInstanceOf[E]
            val _operation: Operation = subfieldOp.operation

  extension (ref: ProbeRecord)
    def getUntypedRefViaFieldValNameImpl(
      refer:        Value,
      fieldValName: String
    )(
      using Arena,
      Block,
      Context,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    )(
      using TypeImpl
    ): Ref[Data] =
      require(!ref.instantiating)
      ref._elements
        .find(_.name == fieldValName)
        .map: field =>
          val openSubfieldOp = summon[OpenSubfieldApi]
            .op(
              input = refer,
              fieldIndex = refer.getType.getBundleFieldIndex(field.name),
              location = locate
            )
          openSubfieldOp.operation.appendToBlock()
          new Ref[Data]:
            val _tpe:       Data      = field.dataType.asInstanceOf[Data]
            val _operation: Operation = openSubfieldOp.operation
        .getOrElse:
          throw new Exception(s"$fieldValName not found in ${ref._elements.map(_.name)}")
  extension (ref: Record)
    def getUntypedRefViaFieldValNameImpl(
      refer:        Value,
      fieldValName: String
    )(
      using Arena,
      Block,
      Context,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    )(
      using TypeImpl
    ): Ref[Data] =
      require(!ref.instantiating)
      ref._elements
        .find(_.name == fieldValName)
        .map: field =>
          val subfieldOp = summon[SubfieldApi]
            .op(
              input = refer,
              fieldIndex = refer.getType.getBundleFieldIndex(field.name),
              location = locate
            )
          subfieldOp.operation.appendToBlock()
          new Ref[Data]:
            val _tpe:       Data      = field.dataType.asInstanceOf[Data]
            val _operation: Operation = subfieldOp.operation
        .getOrElse:
          throw new Exception(s"$fieldValName not found in ${ref._elements.map(_.name)}")
end given
