// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.hw.operation

import org.llvm.mlir.scalalib.capi.ir.{Module as MlirModule, ModuleApi as MlirModuleApi, *, given}
import org.llvm.mlir.scalalib.capi.support.HasOperation
import java.lang.foreign.Arena
import org.llvm.circt.scalalib.dialect.hw.tpe.ModulePort

class Instance(val _operation: Operation)
trait InstanceApi extends HasOperation[Instance]:
  def op(
    name:        String,
    module:      String,
    inputs:      Seq[Value],
    inputNames:  Seq[String],
    resultTypes: Seq[Type],
    resultNames: Seq[String],
    parameters:  Seq[Attribute],
    location:    Location
  )(
    using Arena,
    Context
  ):   Instance
  extension (ref: Instance)
    def result(
      index: Int
    )(
      using Arena
    ): Value

class Constant(val _operation: Operation)
trait ConstantApi extends HasOperation[Constant]:
  def op(
    value:    Attribute,
    location: Location
  )(
    using Arena,
    Context
  ):   Constant
  extension (ref: Constant)
    def result(
      using Arena
    ): Value

class Module(val _operation: Operation)
trait ModuleApi extends HasOperation[Module]:
  def op(
    name:       String,
    ports:      Seq[ModulePort],
    parameters: Seq[Attribute],
    location:   Location
  )(
    using Arena,
    Context
  ):   Module
  extension (ref: Module)
    def block(
      using Arena
    ): Block

/** An interface declaration for a separately compiled module. */
class ModuleExtern(val _operation: Operation)
trait ModuleExternApi extends HasOperation[ModuleExtern]:
  def op(
    name:       String,
    ports:      Seq[ModulePort],
    parameters: Seq[Attribute],
    location:   Location
  )(
    using Arena,
    Context
  ): ModuleExtern

class Output(val _operation: Operation)
trait OutputApi extends HasOperation[Output]:
  def op(
    values:   Seq[Value],
    location: Location
  )(
    using Arena,
    Context
  ): Output

/** `hw.param.value`. */
class ParamValue(val _operation: Operation)
trait ParamValueApi extends HasOperation[ParamValue]:
  def op(
    value:    Attribute,
    location: Location
  )(
    using Arena,
    Context
  ):   ParamValue
  extension (ref: ParamValue)
    def result(
      using Arena
    ): Value

/** `hw.enum.constant`. */
class EnumConstant(val _operation: Operation)
trait EnumConstantApi extends HasOperation[EnumConstant]:
  def op(
    field:      Attribute,
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ):   EnumConstant
  extension (ref: EnumConstant)
    def result(
      using Arena
    ): Value

class TypeScope(val _operation: Operation)
trait TypeScopeApi extends HasOperation[TypeScope]:
  def op(
    name:     String,
    location: Location
  )(
    using Arena,
    Context
  ):   TypeScope
  extension (ref: TypeScope)
    def block(
      using Arena
    ): Block

class TypeDecl(val _operation: Operation)
trait TypeDeclApi extends HasOperation[TypeDecl]:
  def op(
    name:     String,
    tpe:      Type,
    location: Location
  )(
    using Arena,
    Context
  ): TypeDecl

class EnumCmp(val _operation: Operation)
trait EnumCmpApi extends HasOperation[EnumCmp]:
  def op(
    lhs:      Value,
    rhs:      Value,
    location: Location
  )(
    using Arena,
    Context
  ):   EnumCmp
  extension (ref: EnumCmp)
    def result(
      using Arena
    ): Value

class StructCreate(val _operation: Operation)
trait StructCreateApi extends HasOperation[StructCreate]:
  def op(
    values:     Seq[Value],
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ):   StructCreate
  extension (ref: StructCreate)
    def result(
      using Arena
    ): Value

class StructExtract(val _operation: Operation)
trait StructExtractApi extends HasOperation[StructExtract]:
  def op(
    input:      Value,
    fieldIndex: Int,
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ):   StructExtract
  extension (ref: StructExtract)
    def result(
      using Arena
    ): Value

class UnionCreate(val _operation: Operation)
trait UnionCreateApi extends HasOperation[UnionCreate]:
  def op(
    input:      Value,
    fieldIndex: Int,
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ):   UnionCreate
  extension (ref: UnionCreate)
    def result(
      using Arena
    ): Value

class UnionExtract(val _operation: Operation)
trait UnionExtractApi extends HasOperation[UnionExtract]:
  def op(
    input:      Value,
    fieldIndex: Int,
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ):   UnionExtract
  extension (ref: UnionExtract)
    def result(
      using Arena
    ): Value

class ArrayGet(val _operation: Operation)
trait ArrayGetApi extends HasOperation[ArrayGet]:
  def op(
    input:      Value,
    index:      Value,
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ):   ArrayGet
  extension (ref: ArrayGet)
    def result(
      using Arena
    ): Value

class ArrayCreate(val _operation: Operation)
trait ArrayCreateApi extends HasOperation[ArrayCreate]:
  def op(
    inputs:     Seq[Value],
    resultType: Type,
    location:   Location
  )(
    using Arena,
    Context
  ): ArrayCreate
