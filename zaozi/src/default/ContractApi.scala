// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.{ContractApi, ContractTuple, ContractTupleArgs, TypeImpl}
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
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, Operation, Value, given}

import scala.collection.mutable.{ArrayBuffer, ArrayDeque}

import java.lang.foreign.Arena

private enum ContractClauseKind:
  case Require, Ensure

private final case class ContractClause(
  kind:     ContractClauseKind,
  property: Value,
  label:    Option[String],
  location: Location)

private val contractScopes = ArrayDeque.empty[ArrayBuffer[ContractClause]]

export given_ContractApi.{Contract, Ensure, Require}

given ContractApi with
  private def operationIsNull(op: Operation): Boolean = MlirOperation.ptr(op.segment).address == 0
  // Lower all public Contract overloads through a flat Seq, while preserving the
  // user-facing body argument and result shapes through the mapping functions.
  private def mapped[R, O](
    args:    Seq[Referable[? <: Data] & HasOperation],
    mapping: Seq[Referable[? <: Data] & HasOperation] => O
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

    val bodyArgs   = args.zipWithIndex.map: (arg, idx) =>
      val node = summon[NodeApi].op(
        name = "",
        location = locate,
        nameKind = FirrtlNameKind.Droppable,
        input = contract.block.getArgument(idx.toLong)
      )
      node.operation.appendToBlock()(
        using contract.block
      )
      new ContractResult(arg._tpe, node.operation)
    val clauses    = ArrayBuffer.empty[ContractClause]
    val beforeBody =
      if args.isEmpty then
        var current = summon[Block].getFirstOperation
        var last    = Option.empty[Operation]
        while !operationIsNull(current) do
          last = Some(current)
          current = current.getNextInBlock
        last
      else None
    contractScopes.append(clauses)
    try
      body(mapping(bodyArgs))(
        using summon[Arena],
        summon[Context],
        contract.block
      )
    finally
      contractScopes.remove(contractScopes.length - 1)
    if args.isEmpty then
      val bodyOps = ArrayBuffer.empty[Operation]
      var current = beforeBody.map(_.getNextInBlock).getOrElse(summon[Block].getFirstOperation)
      while !operationIsNull(current) do
        val next = current.getNextInBlock
        bodyOps.append(current)
        current = next
      bodyOps.foreach: op =>
        op.removeFromParent()
        contract.block.appendOwnedOperation(op)
    clauses.foreach: clause =>
      val op = clause.kind match
        case ContractClauseKind.Require =>
          summon[VerifRequireApi].op(clause.property, clause.label, clause.location).operation
        case ContractClauseKind.Ensure  =>
          summon[VerifEnsureApi].op(clause.property, clause.label, clause.location).operation
      op.appendToBlock()(
        using contract.block
      )
    contract.operation.appendToBlock()

    val results = args.zipWithIndex.map: (arg, idx) =>
      val node = summon[NodeApi].op(
        name = "",
        location = locate,
        nameKind = FirrtlNameKind.Droppable,
        input = contract.operation.getResult(idx.toLong)
      )
      node.operation.appendToBlock()
      new ContractResult(arg._tpe, node.operation)

    mapping(results)

  def Contract(
    body: => Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Unit =
    mapped[Unit, Unit](Seq.empty, _ => ())(_ => body)

  def Contract[T <: Data](
    arg:  Referable[T] & HasOperation
  )(body: (Referable[T] & HasOperation) => (Arena, Context, Block) ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Referable[T] & HasOperation =
    mapped(
      Seq(arg),
      values => values(0).asInstanceOf[Referable[T] & HasOperation]
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
    property: Immediate | Sequence | Property,
    label:    Option[String] = None
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Unit =
    val value = property match
      case immediate: Immediate => immediate.refer
      case sequence:  Sequence  => sequence.refer
      case prop:      Property  => prop.refer
    if contractScopes.nonEmpty then
      contractScopes.last.append(ContractClause(ContractClauseKind.Require, value, label, locate))
    else summon[VerifAssumeApi].op(value, label, locate).operation.appendToBlock()

  def Ensure(
    property: Immediate | Sequence | Property,
    label:    Option[String] = None
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Unit =
    val value = property match
      case immediate: Immediate => immediate.refer
      case sequence:  Sequence  => sequence.refer
      case prop:      Property  => prop.refer

    if contractScopes.nonEmpty then
      contractScopes.last.append(ContractClause(ContractClauseKind.Ensure, value, label, locate))
    else summon[VerifAssertApi].op(value, label, locate).operation.appendToBlock()
