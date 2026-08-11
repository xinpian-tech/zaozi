// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.TemporalLogic" %s --
// DEFINE: %{bmc} = circt-bmc %t.dir/w4.bmc.hw.mlir --module=TemporalLogic_width4_CheckContract_0 -b 4 --shared-libs=%Z3LIB --run

// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/w4.json --width 4
// RUN: FileCheck %s -check-prefix=CONFIG --input-file=%t.dir/w4.json
// RUN: cd %t.dir && %{test} design %t.dir/w4.json
// RUN: circt-opt %t.dir/TemporalLogic_width4.mlirbc | FileCheck %s -check-prefix=FIRRTL
// RUN: firtool %t.dir/TemporalLogic_width4.mlirbc --hw-pass-plugin='lower-contracts' --output-hw-mlir=%t.dir/w4.contract.hw.mlir --disable-output
// RUN: circt-opt %t.dir/w4.contract.hw.mlir --strip-om --symbol-dce -o %t.dir/w4.clean.hw.mlir
// RUN: FileCheck %s -check-prefix=LOWERED --input-file=%t.dir/w4.clean.hw.mlir
// RUN: circt-opt %t.dir/w4.clean.hw.mlir --pass-pipeline='builtin.module(verif-lower-tests,hw.module(lower-ltl-to-core,lower-seq-shiftreg,lower-seq-compreg-ce,canonicalize))' -o %t.dir/w4.bmc.hw.mlir
// RUN: FileCheck %s -check-prefix=CORE --input-file=%t.dir/w4.bmc.hw.mlir
// RUN: %{bmc} | FileCheck %s -check-prefix=BMC
// RUN: rm -rf %t.dir

// CONFIG: {"width":4}

// FIRRTL-LABEL: firrtl.circuit "TemporalLogic_width4"
// FIRRTL: firrtl.contract
// FIRRTL-COUNT-8: firrtl.int.ltl.clocked_delay
// FIRRTL: firrtl.int.ltl.implication
// FIRRTL: firrtl.int.ltl.and
// FIRRTL: firrtl.int.verif.ensure
// FIRRTL-SAME: one_cycle_delay_matches

// LOWERED-LABEL: verif.formal @TemporalLogic_width4_CheckContract_0
// LOWERED: ltl.clocked_delay
// LOWERED: ltl.implication
// LOWERED: verif.assert
// LOWERED-SAME: one_cycle_delay_matches

// CORE-LABEL: hw.module @TemporalLogic_width4_CheckContract_0
// CORE-NOT: ltl.
// CORE-NOT: seq.shiftreg
// CORE: verif.assert

// BMC: Bound reached with no violations!

package me.jiuyang.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class TemporalLogicParameter(width: Int) extends Parameter:
  require(width > 0, s"width must be positive, got $width")

given upickle.default.ReadWriter[TemporalLogicParameter] = upickle.default.macroRW

class TemporalLogicLayers(parameter: TemporalLogicParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class TemporalLogicIO(parameter: TemporalLogicParameter) extends HWBundle(parameter):
  val clock  = Flipped(Clock())
  val input  = Flipped(Bits(parameter.width))
  val output = Aligned(Bits(parameter.width))

class TemporalLogicProbe(parameter: TemporalLogicParameter)
    extends DVBundle[TemporalLogicParameter, TemporalLogicLayers](parameter)

@generator
object TemporalLogic
    extends Generator[TemporalLogicParameter, TemporalLogicLayers, TemporalLogicIO, TemporalLogicProbe]:
  override def moduleName(parameter: TemporalLogicParameter): String = s"TemporalLogic_width${parameter.width}"

  def architecture(parameter: TemporalLogicParameter) =
    val io           = summon[Interface[TemporalLogicIO]]
    given ClockScope = ClockScope.posedge(io.clock)

    val delayed = Reg(Bits(parameter.width))
    delayed   := io.input
    io.output := delayed

    Contract {
      given ClockEvent = posedge(io.clock)

      val outputFollowsInput = (0 until parameter.width)
        .map: bit =>
          val inputBit  = io.input.bit(bit)
          val outputBit = io.output.bit(bit)
          (inputBit.S |=> outputBit.S) & ((!inputBit).S |=> (!outputBit).S)
        .reduce(_ & _)

      Ensure(outputFollowsInput, "one_cycle_delay_matches")
    }
