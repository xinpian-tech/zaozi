// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class IOMuxPadDemo %s --
// RUN: mkdir -p %t.dir
// RUN: %{test} config %t.dir/config.json
// RUN: cd %t.dir && %{test} design config.json
// RUN: firld --base-circuit=IOMuxPadDemo %t.dir/*.mlirbc -o %t.dir/top.mlir
// RUN: firtool %t.dir/top.mlir --strip-debug-info --split-verilog -o %t.dir/rtl
// RUN: cp %S/Inputs/IOMuxPadModel.sv %t.dir/rtl/
// RUN: FileCheck %s --check-prefix=TOP --input-file=%t.dir/rtl/IOMuxPadDemo.sv
// RUN: cat %t.dir/rtl/IOMuxPadsLibrary_*.sv | FileCheck %s --check-prefix=LIBRARY
// RUN: cat %t.dir/rtl/IOMuxPadsModel_*.sv | FileCheck %s --check-prefix=MODEL

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.iomux.{*, given}
import me.jiuyang.stdlib.mmio.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

// GF180MCU non-PG blackbox views at 40cdef6d74ec5c9b7d596c147df94c98366afc5b.
case class IOMuxPadDemoParameter() extends Parameter:
  val pull = upickle.default.read[IOMuxCellPull]("""{"function":[
    {"pull":"none","PU":0,"PD":0}, {"pull":"up","PU":1,"PD":0}, {"pull":"down","PU":0,"PD":1}
  ]}""")
  val library = IOMuxPadLibrary(Seq("bi_t", "bi_24t").map: kind =>
    IOMuxPadCell(
      IOMuxCell(s"gf180mcu_fd_io__$kind", pull = Some(pull)),
      model = if kind == "bi_t" then "IOMuxPadModel" else "IOMuxPad24Model",
      pad = "PAD", inputValue = Some("Y"), outputValue = Some("A"),
      inputEnable = Some(IOMuxPadEnable("IE", activeHigh = true)),
      outputEnable = Some(IOMuxPadEnable("OE", activeHigh = true)),
      tie = Map("CS" -> false, "SL" -> false) ++
        Option.when(kind == "bi_t")(Map("PDRV0" -> false, "PDRV1" -> false)).toSeq.flatten
    )
  )
  val iomux = IOMuxParameter(
    pinCount = 2, routes = Seq(
      IOMuxRoute(0, 0, cell = IOMuxCellRoute(pull = IOMuxCellSelect(IOMuxPullRequest(), Some(IOMuxPullRequest("up"))))),
      IOMuxRoute(1, 0)
    ),
    hsSlots = 2, dataWidth = 32, addressWidth = 12,
    cells = library.iomuxCells, pinCell = library.iomuxCells.map(_.name)
  )
  val pads = IOMuxPadsParameter(iomux, library)

given upickle.default.ReadWriter[IOMuxPadDemoParameter] = upickle.default.macroRW

class IOMuxPadDemoIO(parameter: IOMuxPadDemoParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val req = Flipped(Decoupled(new IOMuxRequest(parameter.iomux.addressWidth, parameter.iomux.dataWidth)))
  val rsp = Aligned(Decoupled(new RegMapResponse(parameter.iomux.dataWidth, true)))
  val pullUp = Flipped(Bool())
  val inputEnable = Flipped(Bits(2))
  val outputValue = Flipped(Bits(2))
  val outputEnable = Flipped(Bits(2))
  val inputValue = Aligned(Bits(2))
  val pad0 = Inout(Bits(1))
  val pad1 = Inout(Bits(1))

class IOMuxPadDemoLayers(parameter: IOMuxPadDemoParameter) extends LayerInterface(parameter):
  def layers = Seq(parameter.iomux.verification)

class IOMuxPadDemoProbe(parameter: IOMuxPadDemoParameter)
  extends DVBundle[IOMuxPadDemoParameter, IOMuxPadDemoLayers](parameter)

@generator
object IOMuxPadDemo extends Generator[IOMuxPadDemoParameter, IOMuxPadDemoLayers, IOMuxPadDemoIO, IOMuxPadDemoProbe]:
  override def moduleName(parameter: IOMuxPadDemoParameter): String = "IOMuxPadDemo"
  def architecture(parameter: IOMuxPadDemoParameter) =
    val io = summon[Interface[IOMuxPadDemoIO]]
    val mux = IOMux.instantiate(parameter.iomux)
    mux.io.clock := io.clock
    mux.io.resetN := io.resetN
    mux.io.req :<>= io.req
    io.rsp :<>= mux.io.rsp
    mux.io.cell.get.field[Bool]("route_0_pull") := io.pullUp
    mux.io.inputEnable := io.inputEnable
    mux.io.outputValue := io.outputValue
    mux.io.outputEnable := io.outputEnable
    io.inputValue := mux.io.inputValue
    val pads = IOMuxPads(parameter.pads)(mux.io)
    attach(io.pad0, pads.io.field[Analog]("pad_0"))
    attach(io.pad1, pads.io.field[Analog]("pad_1"))

// TOP-LABEL: module IOMuxPadDemo(
// TOP: inout{{.*}} pad0,
// TOP: pad1
// TOP: `ifdef targets$IOMuxPadsIO$Model
// TOP: `ifdef targets$IOMuxPadsIO$Library
// LIBRARY-LABEL: module IOMuxPadsLibrary_
// LIBRARY: gf180mcu_fd_io__bi_t pad0 (
// LIBRARY: .PAD
// LIBRARY: .A
// LIBRARY: .IE
// LIBRARY: .OE
// LIBRARY: .PU
// LIBRARY: .PD
// LIBRARY: .Y
// LIBRARY: gf180mcu_fd_io__bi_24t pad1 (
// MODEL: IOMuxPadModel pad0 (
// MODEL: IOMuxPad24Model pad1 (
