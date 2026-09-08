// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class ProbeForceTest %s --
// RUN: rm -rf %t && mkdir -p %t && cd %t
// RUN: %{test} config %t/config.json --width 8
// RUN: %{test} design %t/config.json
// RUN: firld %t/*.mlirbc --base-circuit ProbeForceTest > %t/design.mlir
// RUN: FileCheck %s --check-prefix=MLIR < %t/design.mlir
// RUN: FileCheck %s --check-prefix=SSA < %t/design.mlir
// RUN: firtool -format=mlir %t/design.mlir | FileCheck %s --check-prefix=SV
// RUN: rm -rf %t

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import java.lang.foreign.Arena

case class ProbeForceParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[ProbeForceParameter] = upickle.default.macroRW

class ProbeForceLayers(parameter: ProbeForceParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Force"))

class ProbeForceIO(parameter: ProbeForceParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val enable = Flipped(Bool())
  val releaseEnable = Flipped(Bool())
  val data = Flipped(UInt(parameter.width))
  val out = Aligned(UInt(parameter.width))

class ProbeForceProbe(parameter: ProbeForceParameter) extends DVBundle[ProbeForceParameter, ProbeForceLayers](parameter):
  val clocked = ProbeReadWrite(UInt(parameter.width), layers("Force"))
  val initial = ProbeReadWrite(UInt(parameter.width), layers("Force"))
  val readOnly = ProbeRead(UInt(parameter.width), layers("Force"))

@generator
object ProbeForceDut extends Generator[ProbeForceParameter, ProbeForceLayers, ProbeForceIO, ProbeForceProbe]:
  def architecture(parameter: ProbeForceParameter) =
    val io = summon[Interface[ProbeForceIO]]
    val probe = summon[ProbeInterface[ProbeForceProbe]]
    given ClockScope = ClockScope.posedge(io.clock)
    val target = Wire(UInt(parameter.width), forceable = true)
    val initialTarget = Reg(UInt(parameter.width), forceable = true)
    target := io.data
    initialTarget := io.data
    io.out := (target.asBits ^ initialTarget.asBits).asUInt
    layer("Force"):
      probe.clocked <== target
      probe.initial <== initialTarget
      probe.readOnly <== target

@generator
object ProbeForceTest extends Generator[ProbeForceParameter, ProbeForceLayers, ProbeForceIO, ProbeForceProbe]:
  override def moduleName(parameter: ProbeForceParameter): String = "ProbeForceTest"
  def architecture(parameter: ProbeForceParameter) =
    val io = summon[Interface[ProbeForceIO]]
    val probe = summon[ProbeInterface[ProbeForceProbe]]
    val dut = ProbeForceDut.instantiate(parameter)
    dut.io.clock := io.clock
    dut.io.enable := io.enable
    dut.io.releaseEnable := io.releaseEnable
    dut.io.data := io.data
    io.out := dut.io.out
    layer("Force"):
      probe.clocked <== dut.probe.clocked
      probe.initial <== dut.probe.initial
      probe.readOnly <== dut.probe.readOnly
      dut.probe.clocked.force(io.data, io.clock, io.enable)
      dut.probe.clocked.release(io.clock, io.releaseEnable)
      dut.probe.initial.forceInitial(0.U(parameter.width), true.B)
      dut.probe.initial.releaseInitial(true.B)

// MLIR-DAG: firrtl.wire {{.*}}forceable
// MLIR-DAG: firrtl.reg {{.*}}forceable
// MLIR-DAG: firrtl.ref.force {{.*}} : !firrtl.clock, !firrtl.uint<1>, !firrtl.rwprobe<uint<8>, @Force>, !firrtl.uint<8>
// MLIR-DAG: firrtl.ref.release {{.*}} : !firrtl.clock, !firrtl.uint<1>, !firrtl.rwprobe<uint<8>, @Force>
// MLIR-DAG: firrtl.ref.force_initial
// MLIR-DAG: firrtl.ref.release_initial
// SSA-NOT: firrtl.ref.rwprobe
// SSA-NOT: sym @
// SV-LABEL: module ProbeForceTest_Force
// SV: always @(posedge ProbeForceTest.clock) begin
// SV: if (ProbeForceTest.enable)
// SV-NEXT: force ProbeForceTest.dut.target = ProbeForceTest.data;
// SV: if (ProbeForceTest.releaseEnable)
// SV-NEXT: release ProbeForceTest.dut.target;
// SV: initial begin
// SV-NEXT: force ProbeForceTest.dut.initialTarget = 8'h0;
// SV-NEXT: release ProbeForceTest.dut.initialTarget;
