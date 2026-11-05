// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

// Two decoders whose tables match the same values but are written differently simplify to one module.
// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class EqualTables %s --
// RUN: rm -rf %t && mkdir -p %t && cd %t
// RUN: %{test} config %t/config.json --width 3
// RUN: %{test} design %t/config.json
// RUN: ls %t | FileCheck %s --check-prefix=FILES
// RUN: firld %t/*.mlirbc --base-circuit EqualTables --no-mangle | firtool --format=mlir | FileCheck %s --check-prefix=VERILOG
// RUN: rm -rf %t

// FILES: PLA_D_{{[0-9a-f]+}}.mlirbc
// FILES-NOT: PLA_D_
// VERILOG: module EqualTables
// VERILOG: PLA_D_[[NAME:[0-9a-f]+]] {{.+}} (
// VERILOG: PLA_D_[[NAME]] {{.+}} (

import me.jiuyang.decoder.*
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class EqualTablesParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[EqualTablesParameter] = upickle.default.macroRW

class EqualTablesLayers(parameter: EqualTablesParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class EqualTablesIO(parameter: EqualTablesParameter) extends HWBundle(parameter):
  val i = Flipped(Bits(parameter.width))
  val a = Aligned(Bits(1))
  val b = Aligned(Bits(1))

class EqualTablesProbe(parameter: EqualTablesParameter)
    extends DVBundle[EqualTablesParameter, EqualTablesLayers](parameter)

@generator
object EqualTables extends Generator[EqualTablesParameter, EqualTablesLayers, EqualTablesIO, EqualTablesProbe]:
  override def moduleName(parameter: EqualTablesParameter): String = "EqualTables"
  def architecture(parameter: EqualTablesParameter) =
    val io = summon[Interface[EqualTablesIO]]
    val first  = PLADecoder.instantiate(DecoderParameter("D", Seq(TruthTable("[t][n][y:1,n:0][1??:y]"))))
    val second = PLADecoder.instantiate(DecoderParameter("D", Seq(TruthTable("[t][n][y:1,n:0][10?|11?:y]"))))
    first.io.instruction  := io.i
    second.io.instruction := io.i
    io.a := first.io.output.field[Bits]("t")
    io.b := second.io.output.field[Bits]("t")
