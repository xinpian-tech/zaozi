// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.default

import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.default.{bundleFieldName, locate, valName}
import me.jiuyang.zaozi.sv.{ConstExpr, ConstructorApi, TypeImpl, TypeScopeContext}
import me.jiuyang.zaozi.sv.valuetpe.*
import me.jiuyang.zaozi.sv.reftpe.*
import org.llvm.circt.scalalib.dialect.hw.attr.{AttributeApi as HWAttributes, given}
import org.llvm.circt.scalalib.dialect.hw.operation.{
  ConstantApi,
  EnumConstantApi,
  ParamValueApi,
  TypeDeclApi,
  TypeScopeApi,
  given
}
import org.llvm.circt.scalalib.dialect.sv.operation.{LocalParamApi, RegApi, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import java.lang.foreign.Arena

given ConstructorApi with
  def UnpackedOpenArray[T <: Data](element: T):                  me.jiuyang.zaozi.sv.valuetpe.UnpackedOpenArray[T] =
    new me.jiuyang.zaozi.sv.valuetpe.UnpackedOpenArray[T]:
      private[zaozi] val _element = element
  extension (value:                  BigInt)
    def B(
      width: Int
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Const[Bits] =
      val tpe = Bits(width)
      val op  = summon[ConstantApi].op(summon[HWAttributes].getInteger(value, tpe.toMlirType), locate)
      op.operation.appendToBlock()
      new Const[Bits]:
        private[zaozi] val _tpe   = tpe
        private[zaozi] val _refer = op.result
  def Bits(width:                    Int):                       Bits                                              = Bits(ConstExpr(width))
  def Bits(width: ConstExpr):                                    Bits                                              = new Bits:
    private[zaozi] val _width = width
  def PackedArray[T <: Data](size:   Int, element: T):           PackedArray[T]                                    = PackedArray(ConstExpr(size), element)
  def PackedArray[T <: Data](size: ConstExpr, element: T):       PackedArray[T]                                    = new PackedArray[T]:
    private[zaozi] val _size    = size
    private[zaozi] val _element = element
  def UnpackedArray[T <: Data](size: Int, element: T):           UnpackedArray[T]                                  = UnpackedArray(ConstExpr(size), element)
  def UnpackedArray[T <: Data](size: ConstExpr, element: T):     UnpackedArray[T]                                  = new UnpackedArray[T]:
    private[zaozi] val _size    = size
    private[zaozi] val _element = element
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
  ): T =
    val name   = valName
    val scope  = new TypeScopeContext(name)
    val result = body(
      using summon[Block],
      scope
    )
    emitTypeDeclarations(scope, summon[Block])
    result
  def TypeDef[T <: Data](
    tpe: T
  )(
    using TypeScopeContext,
    sourcecode.Name.Machine
  ): TypeAlias[T] =
    val scope = summon[TypeScopeContext]
    val name  = bundleFieldName
    val alias = new TypeAlias[T]:
      private[zaozi] val _scope = scope.name
      private[zaozi] val _name  = name
      private[zaozi] val _inner = tpe
    scope.use(alias)
    alias
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
  ): Ref[T] =
    val nativeType = tpe.toMlirType
    require(
      org.llvm.circt.scalalib.capi.dialect.hw.given_TypeApi.isValueType(nativeType)(),
      "CIRCT SV variables require a hardware value type; strings and open arrays are expression values"
    )
    val op         = summon[RegApi].op(nativeType, name = valName, location = locate)
    op.operation.appendToBlock()
    new Ref[T]:
      private[zaozi] val _tpe   = tpe
      private[zaozi] val _refer = op.result
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
  ): Const[Bits] =
    val attribute = expr.toAttribute
    val op        = summon[LocalParamApi].op(attribute, valName, locate)
    op.operation.appendToBlock()
    new Const[Bits]:
      private[zaozi] val _tpe   = Bits(attribute.getType.integerTypeGetWidth)
      private[zaozi] val _refer = op.result
  def ParameterValue(
    expr: ConstExpr
  )(
    using Arena,
    Context,
    Block,
    TypeImpl,
    sourcecode.File,
    sourcecode.Line
  ): Const[Bits] =
    val attribute = expr.toAttribute
    val op        = summon[ParamValueApi].op(attribute, locate)
    op.operation.appendToBlock()
    new Const[Bits]:
      private[zaozi] val _tpe   = Bits(attribute.getType.integerTypeGetWidth)
      private[zaozi] val _refer = op.result

/** Type declarations are emitted after both IO and architecture have registered their types. */
private[sv] def emitTypeDeclarations(
  scope: TypeScopeContext,
  block: Block
)(
  using Arena,
  Context
): Unit =
  def parameterFree(expr: ConstExpr): Boolean = expr match
    case _:   ConstExpr.Literal                => true
    case _:   ConstExpr.MacroValue             => true
    case ref: me.jiuyang.zaozi.sv.ParameterRef => ref.binding.exists(parameterFree)
    case ConstExpr.Expression(_, operands) => operands.forall(parameterFree)
  def independent(tpe: Data):         Boolean = tpe match
    case bits:      Bits             => parameterFree(bits._width)
    case array:     PackedArray[?]   => parameterFree(array._size) && independent(array._element)
    case array:     UnpackedArray[?] => parameterFree(array._size) && independent(array._element)
    case aggregate: Aggregate        => aggregate._fields.forall(f => independent(f.dataType))
    case alias:     TypeAlias[?]     => independent(alias._inner)
    case _:         Enum             => true
  def scopeName(tpe: Data):           String  = tpe match
    case alias: TypeAlias[?] => alias._scope
    case named: NamedType    => named.typeScope
  def typeName(tpe: Data):            String  = tpe match
    case alias: TypeAlias[?] => alias._name
    case named: NamedType    => named.typeName
  def definition(tpe: Data):          Data    = tpe match
    case alias: TypeAlias[?] => alias._inner
    case other => other
  def definitionType(tpe: Data):      Type    = tpe match
    case alias: TypeAlias[?] => alias._inner.toMlirType
    case named: NamedType    => named.rawType
  // Traverse completed declarations now; singleton constructors only register identity.
  val collected = new TypeScopeContext(scope.name)
  scope.declarations.toSeq.foreach(collected.use)
  collected.declarations
    .map(scopeName)
    .distinct
    .foreach: name =>
      val aliases = collected.declarations.filter(scopeName(_) == name)
      var current = block.getFirstOperation
      var existing: Option[Operation] = None
      while org.llvm.mlir.MlirOperation.ptr(current.segment) != java.lang.foreign.MemorySegment.NULL do
        if current.getName.str == "hw.type_scope" && current.getAttributeByName("sym_name").stringAttrGetValue == name
        then existing = Some(current)
        current = current.getNextInBlock
      val scopeOperation = existing.getOrElse:
        val op = summon[TypeScopeApi].op(name, summon[LocationApi].locationUnknownGet)
        op.operation.appendToBlock()(
          using block
        )
        op.operation
      var firstModule    = block.getFirstOperation
      while org.llvm.mlir.MlirOperation
          .ptr(firstModule.segment) != java.lang.foreign.MemorySegment.NULL && firstModule.getName.str != "hw.module"
      do firstModule = firstModule.getNextInBlock
      if org.llvm.mlir.MlirOperation.ptr(firstModule.segment) != java.lang.foreign.MemorySegment.NULL then
        scopeOperation.moveBefore(firstModule)
      val target         = scopeOperation.getRegion(0).getFirstBlock
      aliases.foreach: alias =>
        require(
          independent(definition(alias)),
          s"Typedef ${typeName(alias)} is outside the module and cannot reference module parameters; use an inline data type"
        )
        var declaration = target.getFirstOperation
        var found       = false
        while org.llvm.mlir.MlirOperation.ptr(declaration.segment) != java.lang.foreign.MemorySegment.NULL do
          if declaration.getAttributeByName("sym_name").stringAttrGetValue == typeName(alias) then found = true
          declaration = declaration.getNextInBlock
        if !found then
          summon[TypeDeclApi]
            .op(typeName(alias), definitionType(alias), summon[LocationApi].locationUnknownGet)
            .operation
            .appendToBlock()(
              using target
            )
