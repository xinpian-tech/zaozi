// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{scala} = scala-cli --server=false --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %NATIVEOPTS
// DEFINE: %{test} = %{scala} --main-class ElaborateTop %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir && cd %t.dir
// RUN: mkdir -p %t.dir/modules && echo stale > %t.dir/modules/unrelated.mlirbc
// RUN: %{test} %t.dir/modules %t.dir/top.mlir
// RUN: FileCheck %s --check-prefix=IR --input-file=%t.dir/top.mlir
// RUN: grep -cE "firrtl\.(ext)?module |firrtl\.layer @" %t.dir/top.mlir | FileCheck %s --check-prefix=COUNT
// RUN: firtool --format=mlir %t.dir/top.mlir --strip-debug-info -o %t.dir/design.sv
// RUN: FileCheck %s --check-prefix=CHILD --input-file=%t.dir/design.sv
// RUN: FileCheck %s --check-prefix=TOP --input-file=%t.dir/design.sv
// RUN: mkdir -p %t.dir/child && echo '{"width":7}' > %t.dir/config.json
// RUN: cd %t.dir/child && %{scala} --main-class ElaborateChild %s -- design %t.dir/config.json
// RUN: circt-opt %t.dir/child/ExternalBuffer_*.mlirbc | FileCheck %s --check-prefix=LAYERS
// RUN: circt-opt %t.dir/child/ElaborateChild_*.mlirbc | FileCheck %s --check-prefix=LAYERS
// RUN: rm -rf %t.dir

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ElaborateParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[ElaborateParameter] = upickle.default.macroRW

class ElaborateLayers(parameter: ElaborateParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("A", Seq(Layer("Check"))), Layer("B", Seq(Layer("Check"))), Layer("A", Seq(Layer("Other"))))

class ElaborateIO(parameter: ElaborateParameter) extends HWBundle(parameter):
  val a = Flipped(Bits(parameter.width))
  val b = Aligned(Bits(parameter.width))

class ElaborateProbe(parameter: ElaborateParameter) extends DVBundle[ElaborateParameter, ElaborateLayers](parameter)

case class ElaborateVerilogParameter(WIDTH: Int) extends VerilogParameter

@generator
object ElaborateBuffer
    extends VerilogWrapper[ElaborateParameter, ElaborateLayers, ElaborateIO, ElaborateProbe, ElaborateVerilogParameter]:
  def verilogModuleName(parameter: ElaborateParameter) = "ExternalBuffer"
  def verilogParameter(parameter: ElaborateParameter) = ElaborateVerilogParameter(parameter.width)

@generator
object ElaborateChild extends Generator[ElaborateParameter, ElaborateLayers, ElaborateIO, ElaborateProbe]:
  def architecture(parameter: ElaborateParameter) =
    val io     = summon[Interface[ElaborateIO]]
    val buffer = ElaborateBuffer.instantiate(parameter)
    buffer.io.a := io.a
    io.b := buffer.io.b

@generator
object ElaborateTop extends Generator[ElaborateParameter, ElaborateLayers, ElaborateIO, ElaborateProbe]:
  override def moduleName(parameter: ElaborateParameter): String = "ElaborateTop"
  override def main(args: Array[String]): Unit =
    val result = Elaborate(ElaborateTop, ElaborateParameter(7), os.Path(args(0)))
    val files  = result.modules(result.top) +: (result.modules - result.top).values.toSeq
    os.proc("firld", s"--base-circuit=${result.top}", files, "-o", args(1)).call()
  def architecture(parameter: ElaborateParameter) =
    val io     = summon[Interface[ElaborateIO]]
    val first  = ElaborateChild.instantiate(parameter)
    val second = ElaborateChild.instantiate(parameter)
    first.io.a  := io.a
    second.io.a := first.io.b
    io.b := second.io.b

// IR:     firrtl.circuit "ElaborateTop"
// IR-DAG:  firrtl.extmodule {{.*}}@ExternalBuffer_{{.*}}defname = "ExternalBuffer"
// IR-DAG:  firrtl.module {{.*}}@ElaborateChild_
// IR-DAG:  firrtl.module @ElaborateTop(
// LAYERS:      firrtl.layer @A bind {
// LAYERS-NEXT:   firrtl.layer @Check bind
// LAYERS:        firrtl.layer @Other bind
// LAYERS:      firrtl.layer @B bind {
// LAYERS-NEXT:   firrtl.layer @Check bind

// One declaration of each module and layer.
// COUNT: {{^}}8{{$}}

// CHILD-LABEL: module ElaborateChild_{{[a-f0-9]+}}(
// CHILD: ExternalBuffer #(
// CHILD-NEXT: .WIDTH(7)
// TOP-LABEL: module ElaborateTop(
// TOP: ElaborateChild_{{[a-f0-9]+}} first (
// TOP: ElaborateChild_{{[a-f0-9]+}} second (
