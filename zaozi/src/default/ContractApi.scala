// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.{ContractApi, ContractArgs, InstanceContext, TypeImpl}
import me.jiuyang.zaozi.ltltpe.{Immediate, Property, Sequence}
import me.jiuyang.zaozi.reftpe.Referable

import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  ContractApi as FirrtlContractApi,
  VerifAssertApi,
  VerifAssumeApi,
  VerifEnsureApi,
  VerifRequireApi,
  given
}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Value, given}

import scala.scalanative.unsafe.Zone

export given_ContractApi.{Contract, Ensure, Require}

given ContractApi with
  private def inContract(
    using Zone,
    Block
  ): Boolean =
    Iterator
      .iterate(summon[Block].getParentOperation)(_.getParentOperation)
      .takeWhile(op => !op.isNull)
      .exists(_.getName.str == "firrtl.contract")

  private def propertyValue(
    property: Immediate | Sequence | Property
  )(
    using Zone,
    TypeImpl
  ): Value =
    property match
      case immediate: Immediate => immediate.refer
      case sequence:  Sequence  => sequence.refer
      case prop:      Property  => prop.refer

  def Contract[In <: Referable[?] | Tuple, Out](
    args:        In = EmptyTuple
  )(
    using shape: ContractArgs[In, Out]
  )(body:        Out => (Zone, Context, Block) ?=> Unit
  )(
    using Zone,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Out =
    val inputValues = shape.values(args).map(_.refer)
    val inputTypes  = inputValues.map(_.getType)
    val contract    = summon[FirrtlContractApi].op(inputs = inputValues, resultTypes = inputTypes, location = locate)

    contract.operation.appendToBlock()
    try
      val block    = contract.block
      val bodyArgs = shape.results(args, block.getArgument)
      body(bodyArgs)(
        using summon[Zone],
        summon[Context],
        block
      )
    catch
      case error: Throwable =>
        contract.operation.removeFromParent()
        contract.operation.destroy()
        throw error

    shape.results(args, contract.operation.getResult)

  def Require(
    property: Immediate | Sequence | Property
  )(
    using Zone,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext,
    TypeImpl
  ): Unit =
    Require(property, valName)

  def Ensure(
    property: Immediate | Sequence | Property
  )(
    using Zone,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext,
    TypeImpl
  ): Unit =
    Ensure(property, valName)

  def Require(
    property: Immediate | Sequence | Property,
    label:    String
  )(
    using Zone,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Unit =
    val value = propertyValue(property)
    val op    =
      if inContract then summon[VerifRequireApi].op(value, Some(label), locate).operation
      else summon[VerifAssumeApi].op(value, Some(label), locate).operation
    op.appendToBlock()

  def Ensure(
    property: Immediate | Sequence | Property,
    label:    String
  )(
    using Zone,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Unit =
    val value = propertyValue(property)
    val op    =
      if inContract then summon[VerifEnsureApi].op(value, Some(label), locate).operation
      else summon[VerifAssertApi].op(value, Some(label), locate).operation
    op.appendToBlock()
