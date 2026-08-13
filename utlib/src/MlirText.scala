// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** A minimal reader for the textual `hw`/`comb`/`seq` MLIR that firtool and `circt-verilog`
  * emit, sufficient for the structural recognizers in [[Harvester]].
  *
  * It is deliberately not a general MLIR parser: it reads the flat single-block body of one
  * `hw.module`, indexing SSA definitions by name. Values keep their textual `%name`, so a
  * register defined as `%instr_cnt_q = seq.firreg ...` is referred to by that same string
  * everywhere it is used — the recognizers never have to reconcile a separate symbol table.
  */

/** One SSA-defining operation, reduced to what the recognizers read. */
final case class MlirNode(
  op:       String,
  operands: Seq[String],
  result:   String)

/** A `seq.firreg`/`seq.compreg` state element. */
final case class MlirRegister(
  name:        String,
  driver:      String,
  resetValue:  Option[Int],
  width:       Int)

final class MlirDesign(
  val nodes:     Seq[MlirNode],
  val registers: Seq[MlirRegister],
  private val byResult:  Map[String, MlirNode],
  private val constants: Map[String, BigInt],
  private val icmpPreds: Map[String, String]):

  def node(value:          String): Option[MlirNode] = byResult.get(value)
  def constantValue(value: String): Option[BigInt]   = constants.get(value)
  def icmpPredicate(value: String): Option[String]   = icmpPreds.get(value)

object MlirText:

  private val ssaName = raw"%[A-Za-z0-9_.]+"
  // `%r = comb.icmp bin eq %a, %b : i4` / `%r = comb.icmp eq %a, %b : i4`
  private val icmpLine   = raw"""^\s*($ssaName)\s*=\s*comb\.icmp\s+(?:bin\s+)?(\w+)\s+(.*?)\s*:.*$$""".r
  // `%r = hw.constant 42 : i12`
  private val constLine  = raw"""^\s*($ssaName)\s*=\s*hw\.constant\s+(-?\d+)\s*:\s*i(\d+).*$$""".r
  // `%r = comb.add %a, %b : i4`  (any comb.* other than icmp/constant)
  private val combLine   = raw"""^\s*($ssaName)\s*=\s*(comb\.\w+)\s+(?:bin\s+)?(.*?)\s*:.*$$""".r
  // `%name = seq.firreg %driver clock %c reset (async|sync) %r, %resetval : i4`
  private val regReset   =
    raw"""^\s*($ssaName)\s*=\s*seq\.(?:firreg|compreg)\s+($ssaName)\s+clock\s+$ssaName\s+reset\s+\w+\s+$ssaName,\s+($ssaName)\s*:\s*i(\d+).*$$""".r
  // `%name = seq.firreg %driver clock %c : i4`  (no reset)
  private val regPlain   =
    raw"""^\s*($ssaName)\s*=\s*seq\.(?:firreg|compreg)\s+($ssaName)\s+clock\s+$ssaName\s*:\s*i(\d+).*$$""".r
  private val operandRef = ssaName.r

  /** Parse the body of `hw.module @module`. */
  def parse(text: String, module: String): MlirDesign =
    val lines = bodyLines(text, module)

    val nodes     = scala.collection.mutable.ListBuffer.empty[MlirNode]
    val registers = scala.collection.mutable.ListBuffer.empty[MlirRegister]
    val constants = scala.collection.mutable.Map.empty[String, BigInt]
    val icmpPreds = scala.collection.mutable.Map.empty[String, String]

    lines.foreach {
      case constLine(result, value, _)          =>
        constants(result) = BigInt(value)
        nodes += MlirNode("hw.constant", Seq.empty, result)
      case icmpLine(result, pred, operandText)  =>
        icmpPreds(result) = pred
        nodes += MlirNode("comb.icmp", operandRef.findAllIn(operandText).toSeq, result)
      case regReset(name, driver, resetVal, w)  =>
        registers += MlirRegister(name, driver, constants.get(resetVal).map(_.toInt), w.toInt)
      case regPlain(name, driver, w)            =>
        registers += MlirRegister(name, driver, None, w.toInt)
      case combLine(result, op, operandText)    =>
        nodes += MlirNode(op, operandRef.findAllIn(operandText).toSeq, result)
      case _                                    => ()
    }

    new MlirDesign(
      nodes.toSeq,
      registers.toSeq,
      nodes.map(node => node.result -> node).toMap,
      constants.toMap,
      icmpPreds.toMap
    )

  /** The lines inside `hw.module @module { ... }`, brace-depth tracked from the header. */
  private def bodyLines(text: String, module: String): Seq[String] =
    val all   = text.linesIterator.toSeq
    val start = all.indexWhere(line => line.contains(s"hw.module") && line.contains(s"@$module"))
    require(start >= 0, s"module @$module not found")
    var depth = 0
    var begun = false
    val body  = Seq.newBuilder[String]
    all.drop(start).foreach { line =>
      if !begun then
        if line.contains("{") then begun = true
      else
        body += line
      depth += line.count(_ == '{') - line.count(_ == '}')
      if begun && depth <= 0 then return body.result()
    }
    body.result()
