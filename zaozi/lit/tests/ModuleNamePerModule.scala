// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

// Two equal parameters that name different modules: each name gets its own module.
// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class NamedTop %s --
// RUN: rm -rf %t && mkdir -p %t && cd %t
// RUN: %{test} config %t/config.json --width 4 --tag top
// RUN: %{test} design %t/config.json
// RUN: ls %t | FileCheck %s --check-prefix=FILES
// RUN: firld %t/*.mlirbc --base-circuit NamedTop --no-mangle | firtool --format=mlir | FileCheck %s --check-prefix=VERILOG
// RUN: rm -rf %t

// FILES: Named_x.mlirbc
// FILES: Named_y.mlirbc
// VERILOG: module NamedTop
// VERILOG: Named_x
// VERILOG: Named_y

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class NamedParameter(width: Int, tag: String) extends Parameter:
  override def equals(that: Any): Boolean = that match
    case that: NamedParameter => width == that.width
    case _                    => false
  override def hashCode: Int = width.hashCode
given upickle.default.ReadWriter[NamedParameter] = upickle.default.macroRW

class NamedLayers(parameter: NamedParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class NamedIO(parameter: NamedParameter) extends HWBundle(parameter):
  val a = Flipped(Bits(parameter.width))
  val b = Aligned(Bits(parameter.width))

class NamedProbe(parameter: NamedParameter) extends DVBundle[NamedParameter, NamedLayers](parameter)

@generator
object Named extends Generator[NamedParameter, NamedLayers, NamedIO, NamedProbe]:
  override def moduleName(parameter: NamedParameter): String = s"Named_${parameter.tag}"
  def architecture(parameter: NamedParameter) =
    val io = summon[Interface[NamedIO]]
    io.b := io.a

@generator
object NamedTop extends Generator[NamedParameter, NamedLayers, NamedIO, NamedProbe]:
  override def moduleName(parameter: NamedParameter): String = "NamedTop"
  def architecture(parameter: NamedParameter) =
    val io = summon[Interface[NamedIO]]
    val x  = Named.instantiate(parameter.copy(tag = "x"))
    val y  = Named.instantiate(parameter.copy(tag = "y"))
    x.io.a := io.a
    y.io.a := x.io.b
    io.b := y.io.b
