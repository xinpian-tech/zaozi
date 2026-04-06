// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.stdlib

import mainargs.TokensReader

import me.jiuyang.zaozi.*
import me.jiuyang.decoder.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.testlib.*
import me.jiuyang.decoder.given_ReadWriter_BitSet
import utest.*

case class BitSetSpecParameter(bs: BitSet) extends Parameter:
  def width = bs.width
given upickle.default.ReadWriter[BitSetSpecParameter] = upickle.default.macroRW
given TokensReader.Simple[BitSet]:
  def shortName = "bitset"
  def read(strs: Seq[String]): Right[Nothing, BitSet] = Right(BitSet.bitset(strs.head))

class BitSetSpecLayers(parameter: BitSetSpecParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class BitSetSpecIO(parameter: BitSetSpecParameter) extends HWBundle(parameter):
  val i = Flipped(Bits(parameter.width))
  val o = Aligned(Bool())

class BitSetSpecProbe(parameter: BitSetSpecParameter) extends DVBundle[BitSetSpecParameter, BitSetSpecLayers](parameter)

object BitSetSpec extends TestSuite:
  val tests = Tests:
    test("BitSetApi"):
      test("cover"):
        @generator
        object Cover
            extends Generator[BitSetSpecParameter, BitSetSpecLayers, BitSetSpecIO, BitSetSpecProbe]
            with HasVerilogTest:
          def architecture(parameter: BitSetSpecParameter) =
            val io = summon[Interface[BitSetSpecIO]]
            io.o := parameter.bs.cover(io.i)
        Cover.verilogTest(
          BitSetSpecParameter(
            BitSet(
              Set(
                BitSet.bitpat("101"),
                BitSet.bitpat("?01")
              )
            )
          )
        )(
          "assign o = i == 3'h5 | i[1:0] == 2'h1"
        )
