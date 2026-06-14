// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <clo91eaf@qq.com>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.{ContractApi, ContractTuple, ContractTupleArgs, TypeImpl}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.dialect.verif.operation.{
  AssertApi,
  AssumeApi,
  ContractApi as VerifContractApi,
  EnsureApi,
  RequireApi,
  given
}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, OperationApi, Value, given}

import java.lang.foreign.Arena

private enum ContractClauseKind:
  case Require, Ensure

private final case class ContractClause(
  kind:     ContractClauseKind,
  property: Value,
  label:    Option[String],
  location: Location)

private val contractScopes =
  scala.collection.mutable.ArrayDeque.empty[scala.collection.mutable.ArrayBuffer[ContractClause]]

export given_ContractApi.{Contract, Ensure, Require}

given ContractApi with
  // Lower all public Contract overloads through a flat Seq, while preserving the
  // user-facing body argument and result shapes through the mapping functions.
  private def mapped[R, O](
    args:          Seq[Referable[? <: Data] & HasOperation],
    bodyMapping:   Seq[Referable[? <: Data] & HasOperation] => R,
    resultMapping: Seq[Referable[? <: Data] & HasOperation] => O
  )(body:          R => (Arena, Context, Block) ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): O =
    val inputValues = args.map(_.refer)
    val verifInputs = args
      .zip(inputValues)
      .map: (arg, value) =>
        val resultType = arg._tpe match
          case _:     Bool => 1.integerTypeGet
          case value: UInt => value._width.integerTypeGet
          case value: SInt => value._width.integerTypeGet
          case value: Bits => value._width.integerTypeGet
          case unsupported =>
            throw new UnsupportedOperationException(
              s"verif.contract bridge only supports Bool, UInt, SInt, and Bits; got ${unsupported.getClass.getName}"
            )
        val cast       = summon[OperationApi].operationCreate(
          name = "builtin.unrealized_conversion_cast",
          location = locate,
          operands = Seq(value),
          resultsTypes = Some(Seq(resultType))
        )
        cast.appendToBlock()
        cast.getResult(0)
    val resultTypes = verifInputs.map(_.getType)
    val clauses     = scala.collection.mutable.ArrayBuffer.empty[ContractClause]
    contractScopes.append(clauses)
    try
      body(bodyMapping(args))(
        using summon[Arena],
        summon[Context],
        summon[Block]
      )
    finally
      contractScopes.remove(contractScopes.length - 1)

    val contract = summon[VerifContractApi].op(verifInputs, resultTypes, locate)
    contract.operation.appendToBlock()

    val results = args.zipWithIndex.map: (arg, idx) =>
      val resultCast = summon[OperationApi].operationCreate(
        name = "builtin.unrealized_conversion_cast",
        location = locate,
        operands = Seq(contract.result),
        resultsTypes = Some(Seq(inputValues(idx).getType))
      )
      resultCast.appendToBlock()
      new ContractResult(arg._tpe, resultCast)
    clauses.foreach: clause =>
      val propertyCast = summon[OperationApi].operationCreate(
        name = "builtin.unrealized_conversion_cast",
        location = clause.location,
        operands = Seq(clause.property),
        resultsTypes = Some(Seq(1.integerTypeGet))
      )
      propertyCast.appendToBlock()(
        using contract.block
      )
      val property     = propertyCast.getResult(0)
      clause.kind match
        case ContractClauseKind.Require =>
          summon[RequireApi]
            .op(property, clause.label, clause.location)
            .operation
            .appendToBlock()(
              using contract.block
            )
        case ContractClauseKind.Ensure  =>
          summon[EnsureApi]
            .op(property, clause.label, clause.location)
            .operation
            .appendToBlock()(
              using contract.block
            )

    resultMapping(results)

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
    mapped[Unit, Unit](Seq.empty, _ => (), _ => ())(_ => body)

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
      values => values(0).asInstanceOf[Referable[T] & HasOperation],
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
      _ => args.asInstanceOf[ContractTuple[A]],
      tupleArgs.results
    )(body)

  def Require(
    property: Referable[Bool],
    label:    Option[String] = None
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Unit =
    if contractScopes.nonEmpty then
      contractScopes.last.append(ContractClause(ContractClauseKind.Require, property.refer, label, locate))
    else
      val cast = summon[OperationApi].operationCreate(
        name = "builtin.unrealized_conversion_cast",
        location = locate,
        operands = Seq(property.refer),
        resultsTypes = Some(Seq(1.integerTypeGet))
      )
      cast.appendToBlock()
      summon[AssumeApi].op(cast.getResult(0), label, locate).operation.appendToBlock()

  def Ensure(
    property: Referable[Bool],
    label:    Option[String] = None
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Unit =
    if contractScopes.nonEmpty then
      contractScopes.last.append(ContractClause(ContractClauseKind.Ensure, property.refer, label, locate))
    else
      val cast = summon[OperationApi].operationCreate(
        name = "builtin.unrealized_conversion_cast",
        location = locate,
        operands = Seq(property.refer),
        resultsTypes = Some(Seq(1.integerTypeGet))
      )
      cast.appendToBlock()
      summon[AssertApi].op(cast.getResult(0), label, locate).operation.appendToBlock()
