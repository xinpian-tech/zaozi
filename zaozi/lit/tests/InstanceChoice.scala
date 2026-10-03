// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class ChoiceTop %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/config.json
// RUN: cd %t.dir && %{test} design %t.dir/config.json
// RUN: firld --base-circuit=ChoiceTop %t.dir/*.mlirbc -o %t.dir/top.mlir
// RUN: FileCheck %s --check-prefix=IR --input-file=%t.dir/top.mlir
// RUN: firtool %t.dir/top.mlir --strip-debug-info --split-verilog -o %t.dir/rtl
// RUN: FileCheck %s --check-prefix=RTL --input-file=%t.dir/rtl/ChoiceTop.sv
// RUN: FileCheck %s --check-prefix=LIBRARY --input-file %t.dir/rtl/ClockLibrary_*.sv
// RUN: rm -rf %t.dir

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ChoiceParameter() extends Parameter
given upickle.default.ReadWriter[ChoiceParameter] = upickle.default.macroRW

class ChoiceLayers(parameter: Parameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class ChoiceIO(parameter: Parameter) extends HWBundle(parameter):
  val a        = Flipped(Clock())
  val outClock = Aligned(Clock())

class ChoiceProbe(parameter: Parameter) extends DVBundle[Parameter, ChoiceLayers](parameter)

case class LibraryParameter(cellName: String) extends Parameter
given upickle.default.ReadWriter[LibraryParameter] = upickle.default.macroRW

case class ChoiceVerilogParameter() extends VerilogParameter

@generator
object ClockBuffer extends VerilogWrapper[LibraryParameter, ChoiceLayers, ChoiceIO, ChoiceProbe, ChoiceVerilogParameter]:
  def verilogModuleName(parameter: LibraryParameter) = parameter.cellName
  def verilogParameter(parameter: LibraryParameter) = ChoiceVerilogParameter()

@generator
object ClockModel extends Generator[ChoiceParameter, ChoiceLayers, ChoiceIO, ChoiceProbe]:
  def architecture(parameter: ChoiceParameter) =
    val io = summon[Interface[ChoiceIO]]
    io.outClock := io.a

@generator
object ClockLibrary extends Generator[LibraryParameter, ChoiceLayers, ChoiceIO, ChoiceProbe]:
  def architecture(parameter: LibraryParameter) =
    val io     = summon[Interface[ChoiceIO]]
    val buffer = ClockBuffer.instantiate(parameter)
    buffer.io.a := io.a
    io.outClock := buffer.io.outClock

@generator
object ChoiceChild extends Generator[ChoiceParameter, ChoiceLayers, ChoiceIO, ChoiceProbe]:
  def architecture(parameter: ChoiceParameter) =
    val io     = summon[Interface[ChoiceIO]]
    val buffer = InstanceChoice[ChoiceLayers, ChoiceIO, ChoiceProbe](
      Seq(
        "Model"   -> ClockModel.architecture(parameter),
        "Library" -> ClockLibrary.architecture(LibraryParameter("CKBUFX1"))
      )
    )
    buffer.io.a := io.a
    io.outClock := buffer.io.outClock

@generator
object ChoiceTop extends Generator[ChoiceParameter, ChoiceLayers, ChoiceIO, ChoiceProbe]:
  override def moduleName(parameter: ChoiceParameter): String = "ChoiceTop"
  def architecture(parameter: ChoiceParameter) =
    val io     = summon[Interface[ChoiceIO]]
    val child  = ChoiceChild.instantiate(parameter)
    val buffer = InstanceChoice[ChoiceLayers, ChoiceIO, ChoiceProbe](
      Seq(
        "Model"   -> ClockModel.architecture(parameter),
        "Library" -> ClockLibrary.architecture(LibraryParameter("CKBUFX1"))
      )
    )
    child.io.a  := io.a
    buffer.io.a := child.io.outClock
    io.outClock := buffer.io.outClock

// IR:      firrtl.option @ChoiceIO {
// IR-NEXT:   firrtl.option_case @Model
// IR-NEXT:   firrtl.option_case @Library
// IR-NEXT: }
// IR-NOT:  firrtl.option @

// RTL-LABEL: module ChoiceTop(
// RTL:       `ifdef targets$ChoiceIO$Model
// RTL:         ClockModel_{{[a-f0-9]+}} buffer_Model (
// RTL:       `ifdef targets$ChoiceIO$Library
// RTL:         ClockLibrary_{{[a-f0-9]+}} buffer_Library (

// LIBRARY: CKBUFX1 buffer (
