// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS %s --
// RUN: not %{test} config %t.json --width 32 | FileCheck %s

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.zaozi.default.{*, given}

import java.lang.foreign.Arena

case class PassthroughParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[PassthroughParameter] = upickle.default.macroRW

class PassthroughLayers(parameter: PassthroughParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class PassthroughIO(parameter: PassthroughParameter) extends HWBundle(parameter):
  val i = Flipped(UInt(parameter.width))
  val o = Aligned(UInt(parameter.width))

class PassthroughProbe(parameter: PassthroughParameter)
    extends DVBundle[PassthroughParameter, PassthroughLayers](parameter)

@generator
object PassthroughModule extends Generator[PassthroughParameter, PassthroughLayers, PassthroughIO, PassthroughProbe]:
  def architecture(parameter: PassthroughParameter) =
    val io = summon[Interface[PassthroughIO]]
    io.o := io.i

  def main(args: Array[String]): Unit =
    // CHECK: you can override method `main` and `parseParameter` in generator
    println("you can override method `main` and `parseParameter` in generator")
    this.mainImpl(args)

  def parseParameter(args: Seq[String]): PassthroughParameter =
    // CHECK: This module does not support to construct from command-line
    println("This module does not support to construct from command-line")
    sys.exit(1)
