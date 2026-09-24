// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.TBApi

import org.llvm.circt.scalalib.capi.dialect.hw.{HWModulePort, TypeApi as HWTypeApi, given}
import org.llvm.circt.scalalib.dialect.llhd.operation.{
  ConstantTimeApi,
  DriveApi,
  ProbeApi,
  ProcessApi,
  SignalApi,
  WaitApi,
  given
}
import org.llvm.mlir.scalalib.capi.ir.{
  Attribute,
  AttributeApi,
  Block,
  BlockApi,
  Context,
  Location,
  NamedAttributeApi,
  OperationApi,
  Type,
  TypeApi,
  Value,
  given
}

import java.lang.foreign.Arena

given TBApi with
  def module(
    name: String
  )(body: (Arena, Context, Block) ?=> Unit
  )(
    using Arena,
    Context,
    Block
  ): Unit =
    val op        = summon[OperationApi].operationCreate(
      name = "hw.module",
      location = locate,
      regionBlockTypeLocations = Seq(Seq((Seq.empty[Type], Seq.empty[Location]))),
      namedAttributes = Seq(
        summon[NamedAttributeApi].namedAttributeGet("sym_name".identifierGet, name.stringAttrGet),
        summon[NamedAttributeApi].namedAttributeGet(
          "module_type".identifierGet,
          summon[HWTypeApi].moduleTypeGet(0, Seq.empty[HWModulePort]).typeAttrGet
        ),
        summon[NamedAttributeApi].namedAttributeGet("parameters".identifierGet, Seq.empty[Attribute].arrayAttrGet)
      ),
      resultsTypes = Some(Seq.empty)
    )
    op.appendToBlock()
    val bodyBlock = op.getFirstRegion.getFirstBlock
    body(
      using summon[Arena],
      summon[Context],
      bodyBlock
    )
    summon[OperationApi]
      .operationCreate(name = "hw.output", location = locate, resultsTypes = Some(Seq.empty))
      .appendToBlock()(
        using bodyBlock
      )

  def clock(
    name:   String,
    period: Long
  )(
    using Arena,
    Context,
    Block
  ): Value =
    require(period > 0 && period % 2 == 0, "clock period must be a positive even number of nanoseconds")
    val low     = summon[OperationApi].operationCreate(
      name = "hw.constant",
      location = locate,
      namedAttributes = Seq(summon[NamedAttributeApi].namedAttributeGet("value".identifierGet, false.boolAttrGet)),
      resultsTypes = Some(Seq(1.integerTypeGet))
    )
    low.appendToBlock()
    val high    = summon[OperationApi].operationCreate(
      name = "hw.constant",
      location = locate,
      namedAttributes = Seq(summon[NamedAttributeApi].namedAttributeGet("value".identifierGet, true.boolAttrGet)),
      resultsTypes = Some(Seq(1.integerTypeGet))
    )
    high.appendToBlock()
    val zero    = summon[ConstantTimeApi].op("ns", 0, locate)
    zero.operation.appendToBlock()
    val half    = summon[ConstantTimeApi].op("ns", period / 2, locate)
    half.operation.appendToBlock()
    val sig     = summon[SignalApi].op(low.getResult(0), Some(name), locate)
    sig.operation.appendToBlock()
    val probe   = summon[ProbeApi].op(sig.result, 1.integerTypeGet, locate)
    probe.operation.appendToBlock()
    val process = summon[ProcessApi].op(locate)
    process.operation.appendToBlock()
    val rise    = summon[BlockApi].blockCreate(Seq.empty, Seq.empty)
    val fall    = summon[BlockApi].blockCreate(Seq.empty, Seq.empty)
    process.operation.getFirstRegion.appendOwnedBlock(rise)
    process.operation.getFirstRegion.appendOwnedBlock(fall)
    {
      given Block = process.body
      summon[WaitApi].op(Some(half.result), Seq.empty, rise, locate).operation.appendToBlock()
    }
    {
      given Block = rise
      summon[DriveApi].op(sig.result, high.getResult(0), zero.result, None, locate).operation.appendToBlock()
      summon[WaitApi].op(Some(half.result), Seq.empty, fall, locate).operation.appendToBlock()
    }
    {
      given Block = fall
      summon[DriveApi].op(sig.result, low.getResult(0), zero.result, None, locate).operation.appendToBlock()
      summon[WaitApi].op(Some(half.result), Seq.empty, rise, locate).operation.appendToBlock()
    }
    probe.result
