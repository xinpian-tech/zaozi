// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// RUN: not scala-cli compile %s --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" 2>&1 >/dev/null | FileCheck %s -check-prefix=ERROR

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.capi.dialect.firrtl.DialectApi as FirrtlDialectApi
import org.llvm.circt.scalalib.capi.dialect.firrtl.given_DialectApi

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

  // ERROR: Overriding interface is forbidden
  def interface(parameter: PassthroughParameter) = new PassthroughIO(new PassthroughParameter(32))
