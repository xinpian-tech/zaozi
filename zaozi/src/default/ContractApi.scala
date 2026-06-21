// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.{ContractApi, ContractTuple, ContractTupleArgs, TypeImpl}
import me.jiuyang.zaozi.ltltpe.{Immediate, Property, Sequence}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.{FirrtlNameKind, given}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{NodeApi, given}
import org.llvm.circt.scalalib.dialect.verif.operation.{
  AssertApi,
  AssumeApi,
  ContractApi as VerifContractApi,
  EnsureApi,
  RequireApi,
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

  // The property carried by an Immediate / Sequence / Property has already been
  // converted to a core (i1) / LTL value by the SVA frontend (see SVAApi, which
  // inserts the firrtl -> i1/ltl `unrealized_conversion_cast`), so verif.require
  // / verif.ensure can consume it directly without any further cast.
  private def propertyValue(
    property: Immediate | Sequence | Property
  )(
    using Arena,
    TypeImpl
  ): Value = property match
    case immediate: Immediate => immediate.refer
    case sequence:  Sequence  => sequence.refer
    case prop:      Property   => prop.refer

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
    val outerBlock    = summon[Block]
    val inputValues   = args.map(_.refer)
    val inputTypes    = inputValues.map(_.getType)
    // verif.contract passes its operands through to its results (AllTypesMatch),
    // so the result types mirror the input types.
    val contract      = summon[VerifContractApi].op(inputValues, inputTypes, locate)
    val contractBlock = contract.block
    // Unlike firrtl.contract, verif.contract has no block arguments: its body
    // refers to the contract's results directly (graph region). Expose each
    // result to the body through a pass-through node.
    val bodyArgs      = args.zipWithIndex.map: (arg, idx) =>
      val node = summon[NodeApi].op(
        name = "",
        location = locate,
        nameKind = FirrtlNameKind.Droppable,
        input = contract.operation.getResult(idx.toLong)
      )
      node.operation.appendToBlock()(
        using contractBlock
      )
      new ContractResult(arg._tpe, node.operation)
    val clauses       = ArrayBuffer.empty[ContractClause]
    val beforeBody    =
      if args.isEmpty then
        var current = outerBlock.getFirstOperation
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
        contractBlock
      )
    finally
      contractScopes.remove(contractScopes.length - 1)
    if args.isEmpty then
      val bodyOps = ArrayBuffer.empty[Operation]
      var current = beforeBody.map(_.getNextInBlock).getOrElse(outerBlock.getFirstOperation)
      while !operationIsNull(current) do
        val next = current.getNextInBlock
        bodyOps.append(current)
        current = next
      bodyOps.foreach: op =>
        op.removeFromParent()
        contractBlock.appendOwnedOperation(op)
    clauses.foreach: clause =>
      val op = clause.kind match
        case ContractClauseKind.Require =>
          summon[RequireApi].op(clause.property, clause.label, clause.location).operation
        case ContractClauseKind.Ensure  =>
          summon[EnsureApi].op(clause.property, clause.label, clause.location).operation
      op.appendToBlock()(
        using contractBlock
      )
    contract.operation.appendToBlock()(
      using outerBlock
    )

    val results = args.zipWithIndex.map: (arg, idx) =>
      val node = summon[NodeApi].op(
        name = "",
        location = locate,
        nameKind = FirrtlNameKind.Droppable,
        input = contract.operation.getResult(idx.toLong)
      )
      node.operation.appendToBlock()(
        using outerBlock
      )
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
    val value = propertyValue(property)
    if contractScopes.nonEmpty then
      contractScopes.last.append(ContractClause(ContractClauseKind.Require, value, label, locate))
    else
      summon[AssumeApi].op(value, label, locate).operation.appendToBlock()

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
    val value = propertyValue(property)
    if contractScopes.nonEmpty then
      contractScopes.last.append(ContractClause(ContractClauseKind.Ensure, value, label, locate))
    else
      summon[AssertApi].op(value, label, locate).operation.appendToBlock()
