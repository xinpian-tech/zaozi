// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.default

import me.jiuyang.zaozi.default.locate
import me.jiuyang.zaozi.sv.{
  ConstExpr,
  MacroEnvironment,
  Modport,
  ParameterField,
  ParameterRef,
  TypeImpl,
  VerilogInterface
}
import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.*
import org.llvm.circt.scalalib.capi.dialect.hw.{given_AttributeApi as HWAttr, given_TypeApi as HWType}
import org.llvm.circt.scalalib.dialect.hw.tpe.{TypeApi as HWTypes, given}
import org.llvm.circt.scalalib.dialect.hw.attr.{AttributeApi as HWAttributes, given}
import org.llvm.circt.scalalib.dialect.sv.operation.{ReadInOutApi, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import java.lang.foreign.Arena

given TypeImpl = new SVTypeImpl(Map.empty)

private[sv] class SVTypeImpl(bindings: Map[String, ConstExpr], macros: Option[MacroEnvironment] = None)
    extends TypeImpl:
  private given TypeImpl = this
  extension [T <: Data](ref: Referable[T])
    def valueTypeImpl(
      using Arena,
      Context
    ): Type = ref match
      case value:     ValueReferable[?]                         =>
        val native = value._refer.getType
        if HWType.isInOut(native)() then HWType.inOutTypeGetElementType(native)() else native
      case member:    EnumMember[?]                             => member.enumType.toMlirType
      case parameter: ParameterRef                              => parameter.field.width.integerTypeGet
      case signal:    me.jiuyang.zaozi.sv.InterfaceSignalRef[?] => signal.getType.toMlirType
  extension (tpe:            Data)
    def materializeTypeImpl(
      using Arena,
      Context
    ): Type =
      val raw = tpe.rawType
      tpe match
        case named: NamedType => HWType.typeAliasTypeGet(named.typeScope, named.typeName, raw)
        case _ => raw
  extension (tpe:            SVString.type)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type = summon[HWTypes].getString
  extension [T <: Data](tpe: UnpackedOpenArray[T])
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      import org.llvm.circt.scalalib.dialect.sv.tpe.given
      summon[org.llvm.circt.scalalib.dialect.sv.tpe.TypeApi].getUnpackedOpenArray(tpe._element.toMlirType)
  extension (tpe:            VerilogInterface)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      import org.llvm.circt.scalalib.dialect.sv.tpe.given
      summon[org.llvm.circt.scalalib.dialect.sv.tpe.TypeApi].getInterface(tpe.name)
  extension (tpe:            Modport[?])
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      import org.llvm.circt.scalalib.dialect.sv.tpe.given
      summon[org.llvm.circt.scalalib.dialect.sv.tpe.TypeApi].getModport(tpe.interface.name, tpe.name)
  extension (tpe:            Bits)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type = tpe._width match
      case ConstExpr.Literal(width) => width.integerTypeGet
      case expression               => HWType.paramIntTypeGet(expression.toAttribute)
  extension [T <: Data](tpe: PackedArray[T])
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type = summon[HWTypes].getArray(tpe._element.toMlirType, dimensionAttribute(tpe._size))
  extension [T <: Data](tpe: UnpackedArray[T])
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type = summon[HWTypes].getUnpackedArray(tpe._element.toMlirType, dimensionAttribute(tpe._size))
  extension (tpe:            Enum)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type = summon[HWTypes].getEnum(tpe.members.map(_.name).toSeq)
  extension [T <: Data](tpe: TypeAlias[T])
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type = HWType.typeAliasTypeGet(tpe._scope, tpe._name, tpe._inner.toMlirType)
  private def expressionWidths(expr: ConstExpr): Seq[Int] = expr match
    case parameter: ParameterRef => Seq(parameter.field.width)
    case ConstExpr.Expression(_, operands) => operands.flatMap(expressionWidths)
    case _: ConstExpr.MacroValue => Seq(32)
    case _: ConstExpr.Literal    => Seq.empty

  private def expressionWidth(expr: ConstExpr): Int = expressionWidths(expr).maxOption.getOrElse(32)

  private def expressionAttribute(
    expr:  ConstExpr,
    width: Int
  )(
    using Arena,
    Context
  ): Attribute =
    // Literal operands acquire the expression's integer type. References keep their declared type.
    expr match
      case ConstExpr.Literal(value)               => value.toLong.integerAttrGet(width.integerTypeGet)
      case ConstExpr.MacroValue(name)             =>
        macros match
          case Some(environment) => environment.resolve(name).value.toLong.integerAttrGet(width.integerTypeGet)
          case None              => summon[HWAttributes].getParamVerbatim("`" + name, width.integerTypeGet)
      case parameter: ParameterRef =>
        bindings.get(parameter.field.name).orElse(parameter.binding) match
          case Some(value) => new SVTypeImpl(Map.empty, macros).expressionAttribute(value, width)
          case None        =>
            require(
              parameter.field.width == width,
              s"Parameter ${parameter.field.name} has width ${parameter.field.width}, expected $width; array dimensions require 64-bit parameters"
            )
            summon[HWAttributes].getParamReference(parameter.field.name, width.integerTypeGet)
      case ConstExpr.Expression(opcode, operands) =>
        summon[HWAttributes].getParamExpression(opcode, operands.map(expressionAttribute(_, width)))

  private def dimensionAttribute(
    expr: ConstExpr
  )(
    using Arena,
    Context
  ): Attribute =
    // Match dimension references and bindings to the native array's i64 expression type.
    expressionAttribute(expr, 64)

  extension (expr:           ConstExpr)
    def toAttributeImpl(
      using Arena,
      Context
    ): Attribute = expressionAttribute(expr, expressionWidth(expr))
  extension (parameter:      ParameterField)
    def declarationImpl(
      using Arena,
      Context
    ): Attribute =
      HWAttr.paramDeclAttrGet(
        parameter.name,
        parameter.width.integerTypeGet,
        parameter.default.toLong.integerAttrGet(parameter.width.integerTypeGet)
      )
  extension [T <: Data](ref: Referable[T])
    def referImpl(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Value =
      ref match
        case member:    EnumMember[?]                             =>
          import org.llvm.circt.scalalib.dialect.hw.operation.given
          val tpe = member.enumType.toMlirType
          val op  = summon[org.llvm.circt.scalalib.dialect.hw.operation.EnumConstantApi]
            .op(summon[HWAttributes].getEnumField(member.name, tpe), tpe, locate)
          op.operation.appendToBlock()
          op.result
        case signal:    me.jiuyang.zaozi.sv.InterfaceSignalRef[?] =>
          import org.llvm.circt.scalalib.dialect.sv.operation.given
          val op =
            // Native member ops accept interface values only; modport ports retain their own value reference.
            if signal.modport.nonEmpty then
              summon[org.llvm.circt.scalalib.dialect.sv.operation.VerbatimExprApi]
                .op(
                  "{{0}}." + signal.signal.name,
                  Seq(signal.interfaceValue),
                  Seq.empty,
                  signal.getType.toMlirType,
                  locate
                )
                .operation
            else
              summon[org.llvm.circt.scalalib.dialect.sv.operation.ReadInterfaceSignalApi]
                .op(signal.interfaceValue, signal.signal.name, signal.getType.toMlirType, locate)
                .operation
          op.appendToBlock()
          op.getResult(0)
        case parameter: ParameterRef                              =>
          val op = org.llvm.circt.scalalib.dialect.hw.operation.given_ParamValueApi.op(parameter.toAttribute, locate)
          op._operation.appendToBlock()
          op._operation.getResult(0)
        case value:     ValueReferable[?]                         =>
          if HWType.isInOut(value._refer.getType)() then
            val read = summon[ReadInOutApi].op(value._refer, locate)
            read.operation.appendToBlock()
            read.result
          else value._refer
  extension (tpe:            Struct)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      summon[HWTypes].getStruct(tpe._fields.toSeq.map(f => f.name -> f.dataType.toMlirType))
  extension (tpe:            Union)
    def toMlirTypeImpl(
      using Arena,
      Context
    ): Type =
      summon[HWTypes].getUnion(tpe._fields.toSeq.map(f => f.name -> f.dataType.toMlirType))
