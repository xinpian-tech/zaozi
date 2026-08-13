// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*

import utest.*

/** The structure harvester on real third-party RTL: the CV32E40X Zc sequencer.
  *
  * Stage 1 (this suite): with no hints, recognize the module's FSM register and its bounded
  * counter register, and classify the counter's update as the increment/clear/hold shape a
  * counter-bound invariant relies on. This is the structural half of harvesting the fact
  * that closes HWMCC's p530.
  */
object HarvesterTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)
  private val resources  = os.Path(sys.props("zaozi.utlib.resourceDir")) / "cv32e40x"

  private lazy val sequencerMlir: os.Path =
    val outDir = outputRoot / "harvester"
    os.makeDir.all(outDir)
    val out = outDir / "sequencer.mlir"
    os.proc(
      "circt-verilog",
      "--ir-hw",
      (resources / "cv32e40x_pkg.sv").toString,
      (resources / "cv32e40x_sequencer.sv").toString,
      "-o",
      out.toString
    ).call()
    out

  val tests: Tests = Tests:
    test("the harvester recognizes the sequencer's FSM and bounded counter"):
      val harvest = Harvester.harvest(sequencerMlir, "cv32e40x_sequencer")

      val counter = harvest.counters.find(_.name == "instr_cnt_q").getOrElse(
        throw new java.lang.AssertionError(s"instr_cnt_q not recognized; found ${harvest.counters.map(_.name)}")
      )
      // Its update is exactly the increment/clear/hold shape.
      assert(counter.width == 4)
      assert(counter.increments)
      assert(counter.clears)

      val fsm = harvest.fsms.find(_.name == "seq_state_q").getOrElse(
        throw new java.lang.AssertionError(s"seq_state_q not recognized; found ${harvest.fsms.map(_.name)}")
      )
      // Its next value is selected against its own value by equality tests, and it resets to
      // a constant state (S_IDLE = 0).
      assert(fsm.width == 4)
      assert(fsm.resetState.contains(0))
