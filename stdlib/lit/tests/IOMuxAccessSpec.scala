// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class IOMuxAccess %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir/32 %t.dir/64
// RUN: %{test} config %t.dir/32/config.json --dataWidth 32
// RUN: %{test} config %t.dir/64/config.json --dataWidth 64
// RUN: cd %t.dir/32 && %{test} design config.json
// RUN: cd %t.dir/64 && %{test} design config.json
// RUN: for width in 32 64; do firtool %t.dir/$width/IOMuxAccess.mlirbc --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t.dir/$width/design.hw.mlir --disable-output; done
// RUN: for width in 32 64; do sed -E -e '/hw.instance "verification"/d' -e '/sv.bind /d' %t.dir/$width/design.hw.mlir > %t.dir/$width/nobind.hw.mlir; done
// RUN: for width in 32 64; do circt-opt %t.dir/$width/nobind.hw.mlir --strip-emit --strip-om --symbol-dce -o %t.dir/$width/nomacro.hw.mlir; done
// RUN: for width in 32 64; do sed -E -e '/sv.macro.decl/d' -e 's/ sym @[A-Za-z0-9_.$-]+//' %t.dir/$width/nomacro.hw.mlir > %t.dir/$width/noinner.hw.mlir; done
// RUN: for width in 32 64; do circt-opt %t.dir/$width/noinner.hw.mlir --canonicalize --cse -o %t.dir/$width/clean.hw.mlir; done
// RUN: circt-bmc %t.dir/32/clean.hw.mlir --module=IOMuxAccess_CheckContract_0 -b 10 --ignore-asserts-until=2 --shared-libs=%Z3LIB --run | FileCheck %s
// RUN: circt-bmc %t.dir/64/clean.hw.mlir --module=IOMuxAccess_CheckContract_0 -b 10 --ignore-asserts-until=2 --shared-libs=%Z3LIB --run | FileCheck %s
// RUN: rm -rf %t.dir

// CHECK: Bound reached with no violations!

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.iomux.*
import me.jiuyang.stdlib.mmio.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class IOMuxAccessParameter(dataWidth: Int) extends Parameter:
  val iomux = IOMuxParameter(pinCount = 1, hsSlots = 2, dataWidth = dataWidth, addressWidth = 9, impId = BigInt("fedcba9876543210", 16))

given upickle.default.ReadWriter[IOMuxAccessParameter] = upickle.default.macroRW

class IOMuxAccessLayers(parameter: IOMuxAccessParameter) extends LayerInterface(parameter):
  def layers = Seq(parameter.iomux.verification)

class IOMuxAccessIO(parameter: IOMuxAccessParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val req = Flipped(Decoupled(new RegMapRequest(parameter.iomux.regMap.indexWidth, parameter.dataWidth)))
  val rsp = Aligned(Decoupled(new RegMapResponse(parameter.dataWidth, true)))

class IOMuxAccessProbe(parameter: IOMuxAccessParameter)
    extends DVBundle[IOMuxAccessParameter, IOMuxAccessLayers](parameter)

@generator
object IOMuxAccess extends Generator[IOMuxAccessParameter, IOMuxAccessLayers, IOMuxAccessIO, IOMuxAccessProbe]:
  override def moduleName(parameter: IOMuxAccessParameter): String = "IOMuxAccess"

  def architecture(parameter: IOMuxAccessParameter) =
    val io = summon[Interface[IOMuxAccessIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)
    val iomux = parameter.iomux
    val selector = RegInit(0.B(1))
    val accesses = iomux.identity.map((_, field, value) => field.read(value.B(field.width))) ++
      iomux.selectors.head.flatMap(field => Seq(field.read(selector), field.write(selector)))
    iomux.regMap(io.req, io.rsp)(accesses*)

    val bytes = parameter.dataWidth / 8
    val index = io.req.bits.index
    val selectorHit = index === (0x100 / bytes).U(iomux.regMap.indexWidth)
    val outside = index >= (0x108 / bytes).U(iomux.regMap.indexWidth)
    val hole = (index >= ((8 + bytes - 1) / bytes).U(iomux.regMap.indexWidth)) & !selectorHit & !outside
    val identityWords = Seq(0x10, 0x32, 0x54, 0x76, 0x98, 0xba, 0xdc, 0xfe)
      .grouped(bytes).map(words =>
        words.zipWithIndex.map((byte, lane) => BigInt(byte) << (8 * lane)).reduce(_ | _)
      ).toSeq
    val identityData = identityWords.zipWithIndex.map((word, i) =>
      (index === BigInt(i).U(iomux.regMap.indexWidth)) ? (word.B(parameter.dataWidth), BigInt(0).B(parameter.dataWidth))
    ).reduce(_ | _)
    val accepted = io.req.valid & io.req.ready
    val selectorWrite = accepted & !io.req.bits.read & selectorHit & io.req.bits.mask.bit(0)

    val previousReset = Reg(Bool())
    val wasAccepted = RegInit(false.B)
    val expectedRead = Reg(Bool())
    val expectedError = Reg(Bool())
    val expectedZero = Reg(Bool())
    val wasStalled = RegInit(false.B)
    val heldRead = Reg(Bool())
    val heldData = Reg(Bits(parameter.dataWidth))
    val heldError = Reg(Bool())
    val wasSelectorWrite = RegInit(false.B)
    val selectedSlot = Reg(Bits(1))
    val heldSelector = Reg(Bits(1))
    val wasSelectorRead = RegInit(false.B)
    val wasIdentityRead = RegInit(false.B)
    val expectedIdentity = Reg(Bits(parameter.dataWidth))
    previousReset := io.reset.asBool
    wasAccepted := accepted
    expectedRead := io.req.bits.read
    expectedError := outside
    expectedZero := hole | outside
    wasStalled := io.rsp.valid & !io.rsp.ready
    heldRead := io.rsp.bits.read
    heldData := io.rsp.bits.data
    heldError := io.rsp.bits.error.get
    wasSelectorWrite := selectorWrite
    selectedSlot := io.req.bits.data.bits(0, 0)
    heldSelector := selector
    wasSelectorRead := accepted & io.req.bits.read & selectorHit

    wasIdentityRead := accepted & io.req.bits.read & (index < BigInt(identityWords.size).U(iomux.regMap.indexWidth))
    expectedIdentity := identityData

    val answered = !wasAccepted | (io.rsp.valid & (io.rsp.bits.read === expectedRead) &
      (io.rsp.bits.error.get === expectedError) & (!expectedZero | (io.rsp.bits.data === BigInt(0).B(parameter.dataWidth))))
    val stable = !wasStalled | (io.rsp.valid & (io.rsp.bits.read === heldRead) &
      (io.rsp.bits.data === heldData) & (io.rsp.bits.error.get === heldError))
    val selected = !wasSelectorWrite | (selector === selectedSlot)
    val unchanged = wasSelectorWrite | (selector === heldSelector)
    val readback = !wasSelectorRead | (io.rsp.bits.data === heldSelector.pad(parameter.dataWidth))
    val identityReadback = !wasIdentityRead | (io.rsp.bits.data === expectedIdentity)
    val active = !previousReset & !io.reset.asBool
    Contract((active, answered, stable, selected, unchanged, readback, identityReadback)):
      case (active, answered, stable, selected, unchanged, readback, identityReadback) =>
        Ensure((!active | answered).I)
        Ensure((!active | stable).I)
        Ensure((!active | selected).I)
        Ensure((!active | unchanged).I)
        Ensure((!active | readback).I)
        Ensure((!active | identityReadback).I)
