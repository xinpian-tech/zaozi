// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.stdlib.default

import mainargs.TokensReader
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.decoder.*
import org.llvm.mlir.scalalib.capi.ir.Block
import org.llvm.mlir.scalalib.capi.ir.Context

import java.lang.foreign.Arena

case class DecoderParameter(name: String, tables: Seq[TruthTable]) extends Parameter:
  lazy val pla: PLA = espresso(tables)

// json
given upickle.default.ReadWriter[DecoderParameter] =
  upickle.default
    .readwriter[ujson.Value]
    .bimap[DecoderParameter](
      decoder =>
        ujson.Obj(
          "name"   -> decoder.name,
          "tables" -> ujson.Arr(decoder.tables.map(_.toString).map(ujson.Str.apply)*)
        ),
      json =>
        DecoderParameter(
          json.obj("name").str,
          json.obj("tables").arr.map(v => TruthTable(v.str)).toSeq
        )
    )

// cli
given TokensReader.Simple[TruthTable]:
  def shortName = "truth-table"
  def read(strs: Seq[String]): Right[Nothing, TruthTable] = Right(TruthTable(strs.head))

class DecoderLayers(parameter: DecoderParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class DecoderOutput(tables: Seq[TruthTable]) extends Record:
  tables.map(t => Aligned(t.name, Bits(t.outputBits)))

class DecoderIO(parameter: DecoderParameter) extends HWRecord(parameter):
  Flipped("instruction", Bits(parameter.pla.inputWidth))
  Aligned("output", new DecoderOutput(parameter.tables))

extension (decoderIO: Interface[DecoderIO])
  def instruction(
    using Arena,
    Block,
    Context
  ) = decoderIO.field[Bits]("instruction")
  def output(
    using Arena,
    Block,
    Context
  ) = decoderIO.field[DecoderOutput]("output")

class DecoderProbe(parameter: DecoderParameter) extends DVBundle[DecoderParameter, DecoderLayers](parameter)
