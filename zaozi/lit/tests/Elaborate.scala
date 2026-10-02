// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class ElaborateTop %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir && cd %t.dir
// RUN: %{test} > %t.dir/top.mlir
// RUN: FileCheck %s --check-prefix=IR --input-file=%t.dir/top.mlir
// RUN: grep -cE "firrtl\.(ext)?module |firrtl\.layer @" %t.dir/top.mlir | FileCheck %s --check-prefix=COUNT
// RUN: firtool --format=mlir %t.dir/top.mlir --strip-debug-info | FileCheck %s --check-prefix=RTL
// RUN: ls %t.dir | FileCheck %s --check-prefix=FILES
// RUN: rm -rf %t.dir

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_DialectApi, DialectApi as FirrtlDialect}
import org.llvm.circt.scalalib.capi.dialect.ltl.{given_DialectApi, DialectApi as LtlDialect}
import org.llvm.circt.scalalib.capi.dialect.verif.{given_DialectApi, DialectApi as VerifDialect}
import org.llvm.mlir.scalalib.capi.ir.{ContextApi, given}

import java.lang.foreign.Arena

case class ElaborateParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[ElaborateParameter] = upickle.default.macroRW

class ElaborateLayers(parameter: ElaborateParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

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
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given org.llvm.mlir.scalalib.capi.ir.Context = summon[ContextApi].contextCreate
      summon[FirrtlDialect].loadDialect
      summon[LtlDialect].loadDialect
      summon[VerifDialect].loadDialect
      val module = Elaborate(ElaborateTop, ElaborateParameter(7))
      require(module.getOperation.verify)
      module.getOperation.print(print)
    finally arena.close()
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
// IR-DAG:  firrtl.layer @Verification

// One declaration of each module and layer.
// COUNT: {{^}}4{{$}}

// RTL-LABEL: module ElaborateChild_{{[a-f0-9]+}}(
// RTL: ExternalBuffer #(
// RTL-NEXT: .WIDTH(7)
// RTL-LABEL: module ElaborateTop(
// RTL: ElaborateChild_{{[a-f0-9]+}} first (
// RTL: ElaborateChild_{{[a-f0-9]+}} second (

// FILES-NOT: mlirbc
