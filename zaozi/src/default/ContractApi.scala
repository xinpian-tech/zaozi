// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.{ContractApi, ContractTuple, ContractTupleArgs, InstanceContext, TypeImpl}
import me.jiuyang.zaozi.ltltpe.{Immediate, Property, Sequence}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.{FirrtlNameKind, given}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  ContractApi as FirrtlContractApi,
  NodeApi,
  VerifAssertApi,
  VerifAssumeApi,
  VerifEnsureApi,
  VerifRequireApi,
  given
}
import org.llvm.mlir.MlirOperation
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Value, given}

import java.lang.foreign.Arena

export given_ContractApi.{Contract, Ensure, Require}

given ContractApi with
  private def inContract(
    using Arena,
    Block
  ): Boolean =
    Iterator
      .iterate(summon[Block].getParentOperation)(_.getParentOperation)
      .takeWhile(op => MlirOperation.ptr(op.segment).address != 0)
      .exists(_.getName.str == "firrtl.contract")

  private def propertyValue(
    property: Immediate | Sequence | Property
  )(
    using Arena,
    TypeImpl
  ): Value =
    property match
      case immediate: Immediate => immediate.refer
      case sequence:  Sequence  => sequence.refer
      case prop:      Property  => prop.refer

  // Lower all public Contract overloads through a flat Seq, while preserving the
  // user-facing body argument and result shapes through the mapping functions.
  private def mapped[O](
    args:    Seq[Referable[? <: Data]],
    mapping: Seq[Referable[? <: Data]] => O
  )(body:    O => (Arena, Context, Block) ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): O =
    val inputValues = args.map(_.refer)
    val inputTypes  = inputValues.map(_.getType)
    val contract    = summon[FirrtlContractApi].op(inputs = inputValues, resultTypes = inputTypes, location = locate)

    contract.operation.appendToBlock()
    try
      val bodyArgs = args.zipWithIndex.map: (arg, idx) =>
        val node = summon[NodeApi].op(
          name = "",
          location = locate,
          nameKind = FirrtlNameKind.Droppable,
          input = contract.block.getArgument(idx.toLong)
        )
        node.operation.appendToBlock()(
          using contract.block
        )
        new ContractResult(arg._tpe, node.operation.getResult(0))
      body(mapping(bodyArgs))(
        using summon[Arena],
        summon[Context],
        contract.block
      )
    catch
      case error: Throwable =>
        contract.operation.removeFromParent()
        contract.operation.destroy()
        throw error

    val results = args.zipWithIndex.map: (arg, idx) =>
      val node = summon[NodeApi].op(
        name = "",
        location = locate,
        nameKind = FirrtlNameKind.Droppable,
        input = contract.operation.getResult(idx.toLong)
      )
      node.operation.appendToBlock()
      new ContractResult(arg._tpe, node.operation.getResult(0))

    mapping(results)

  def Contract(
  )(body: (Arena, Context, Block) ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Unit =
    mapped[Unit](Seq.empty, _ => ())(_ => body)

  def Contract[T <: Data](
    arg:  Referable[T]
  )(body: (Referable[T]) => (Arena, Context, Block) ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Referable[T] =
    mapped(
      Seq(arg),
      values => values(0).asInstanceOf[Referable[T]]
    )(body)

  def Contract[A <: Tuple](
    args:            A
  )(body:            ContractTuple[A] => (Arena, Context, Block) ?=> Unit
  )(
    using tupleArgs: ContractTupleArgs[A]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): ContractTuple[A] =
    mapped(
      tupleArgs.values(args),
      tupleArgs.results
    )(body)

  def Require(
    property: Immediate | Sequence | Property
  )(
    using Arena,
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
    using Arena,
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
    using Arena,
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
    using Arena,
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
