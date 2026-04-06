// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

// RUN: not scala-cli compile %s --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" 2>&1 >/dev/null | FileCheck %s -check-prefix=ERROR

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.zaozi.default.{*, given}

import java.lang.foreign.Arena

// ERROR: PassthroughParameterA should be a case class
class PassthroughParameterA(val width: Int) extends Parameter

class PassthroughLayersA(parameter: PassthroughParameterA) extends LayerInterface(parameter):
  def layers = Seq.empty

class PassthroughIOA(parameter: PassthroughParameterA) extends HWBundle(parameter):
  val i = Flipped(UInt(parameter.width))
  val o = Aligned(UInt(parameter.width))

class PassthroughProbeA(parameter: PassthroughParameterA)
    extends DVBundle[PassthroughParameterA, PassthroughLayersA](parameter)

@generator
object PassthroughA extends Generator[PassthroughParameterA, PassthroughLayersA, PassthroughIOA, PassthroughProbeA]:
  def architecture(parameter: PassthroughParameterA) = ()

// ERROR: Multiple parameter lists not supported
case class PassthroughParameterB(width: Int)() extends Parameter

class PassthroughLayersB(parameter: PassthroughParameterB) extends LayerInterface(parameter)

class PassthroughIOB(parameter: PassthroughParameterB) extends HWBundle(parameter):
  val i = Flipped(UInt(parameter.width))
  val o = Aligned(UInt(parameter.width))

class PassthroughProbeB(parameter: PassthroughParameterB)
    extends DVBundle[PassthroughParameterB, PassthroughLayersB](parameter)

@generator
object PassthroughB extends Generator[PassthroughParameterB, PassthroughLayersB, PassthroughIOB, PassthroughProbeB]:
  def architecture(parameter: PassthroughParameterB) = ()
