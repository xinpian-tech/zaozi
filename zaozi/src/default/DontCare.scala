// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import scala.annotation.tailrec

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.{Interface, Writable}
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.given
import org.llvm.circt.scalalib.dialect.firrtl.operation.{InvalidValueApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Value, given}

import java.lang.foreign.Arena

private enum Flow:
  case Source, Sink, Duplex

@tailrec
private def rootFlow(
  v:     Value,
  flips: Int = 0
)(
  using Arena,
  Context
): (Flow, Int) =
  if v.isBlockArgument then
    val moduleOp = v.blockArgumentGetOwner.getParentOperation
    val argNum   = v.blockArgumentGetArgNumber
    val isOut    = moduleOp.getInherentAttributeByName("portDirections").denseBoolArrayGetElement(argNum)
    (if isOut then Flow.Sink else Flow.Source, flips)
  else if v.isOpResult then
    val op = v.opResultGetOwner
    op.getName.str match
      case "firrtl.subfield"                                =>
        val idx   = op.getInherentAttributeByName("fieldIndex").integerAttrGetValueInt.toInt
        val input = op.getOperand(0)
        rootFlow(input, if input.getType.getBundleFieldByIndex(idx).getIsFlip then flips ^ 1 else flips)
      case "firrtl.subindex" | "firrtl.subaccess"           =>
        rootFlow(op.getOperand(0), flips)
      case "firrtl.instance"                                =>
        val resultNum = v.opResultGetResultNumber
        val isOut     = op.getInherentAttributeByName("portDirections").denseBoolArrayGetElement(resultNum)
        (if isOut then Flow.Source else Flow.Sink, flips)
      case "firrtl.wire" | "firrtl.reg" | "firrtl.regreset" =>
        (Flow.Duplex, flips)
      case "firrtl.node" | "firrtl.constant"                =>
        (Flow.Source, flips)
      case _                                                =>
        (Flow.Duplex, flips)
  else (Flow.Duplex, flips)

private def writable(base: (Flow, Int), moreFlips: Int): Boolean =
  val (flow, baseFlips) = base
  flow match
    case Flow.Duplex => true
    case Flow.Source => ((baseFlips + moreFlips) & 1) == 1
    case Flow.Sink   => ((baseFlips + moreFlips) & 1) == 0

private def dontCareInto(
  dest:      Value,
  tpe:       Data,
  baseFlow:  Flow,
  baseFlips: Int,
  typeNet:   Boolean
)(
  using Arena,
  Context,
  Block,
  TypeImpl,
  sourcecode.File,
  sourcecode.Line
): Unit =
  if isPassive(tpe) then
    if writable((baseFlow, baseFlips), (if typeNet then 1 else 0)) then
      val invalidOp = summon[InvalidValueApi].op(dest.getType, locate)
      invalidOp.operation.appendToBlock()
      rawConnect(dest, invalidOp.result)
  else
    tpe match
      case v:   Vec[?]    =>
        (0 until v.count).foreach(i =>
          dontCareInto(subindexValue(dest, i), v.elementType, baseFlow, baseFlips, typeNet)
        )
      case agg: Aggregate =>
        agg.elements.zipWithIndex.foreach: (f, i) =>
          dontCareInto(subfieldValue(dest, i), f.dataType, baseFlow, baseFlips, typeNet ^ f.isFlipped)
      case _ => ()

private def dontCareValue(
  dest: Value,
  tpe:  Data
)(
  using Arena,
  Context,
  Block,
  TypeImpl,
  sourcecode.File,
  sourcecode.Line
): Unit =
  val (baseFlow, baseFlips) = rootFlow(dest)
  dontCareInto(dest, tpe, baseFlow, baseFlips, false)

private[zaozi] def interfaceDontCare(
  sink: Interface[?]
)(
  using Arena,
  Context,
  Block,
  TypeImpl,
  sourcecode.File,
  sourcecode.Line
): Unit =
  sink.getType
    .asInstanceOf[Aggregate]
    .elements
    .zipWithIndex
    .foreach: (f, idx) =>
      val port = sink.portRef(idx).refer
      dontCareValue(port, f.dataType)

given [D <: Data, SINK <: Writable[D]]: DontCare[D, SINK] with
  extension (ref: SINK)
    def dontCare(
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Unit =
      dontCareValue(ref.refer, ref.getType)
