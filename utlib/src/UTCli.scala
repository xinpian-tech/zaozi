// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import me.jiuyang.zaozi.*

/** The contract an LLM-generated experiment implements: one object named `Generated` in the default package, running
  * one solve-and-save and reporting as JSON. The experiments module's fixed runner invokes it; everything else about
  * the generated file (the UT module, its constraint) is free-form typed DSL.
  */
trait UTExperiment:
  def run(outDir: os.Path): ujson.Value

/** The machine-facing side of the formal-UT flow: one call from constraint to artifacts, reported as JSON an external
  * harness (the LLM repair loop) parses — never prose.
  */
object UTCli:

  /** Solve `dut`'s generation constraint (its body asserts ¬C) and save the witness as Model B `stimulus.txt`.
    *
    * Reports `{"status": "generated", "dut": …, "cycles": …, "trace": {signal: [values…]}, "stimulusFile": …}` on a
    * witness, `{"status": "infeasible"}` when no trace within the bound satisfies C, `{"status": "unknown",
    * "detail": …}` when circt-bmc could not decide.
    */
  def generateReport[
    PARAM <: Parameter,
    L <: LayerInterface[PARAM],
    I <: HWInterface[PARAM],
    P <: DVInterface[PARAM, L]
  ](dut:       Generator[PARAM, L, I, P] & UT[PARAM, I],
    parameter: PARAM,
    bound:     Int,
    outDir:    os.Path
  ): ujson.Value =
    val spec = UTGenerator(dut, parameter, outDir).abi.spec
    // The drive ports are delayed in the model so witness time equals replay time (see FormalUT.delayInputs).
    FormalUT.generateGenerator(dut, parameter, bound, outDir, delayedDrives = spec.drive.map(_.name)) match
      case GenerateOutcome.Generated(txn) =>
        val stimulus = Stimulus.save(txn, spec, outDir / "stimulus.txt")
        ujson.Obj(
          "status"       -> "generated",
          "dut"          -> spec.dut,
          "cycles"       -> txn.cycles,
          "trace"        -> ujson.Obj.from(txn.values.map((k, v) => k -> ujson.Arr.from(v.map(_.toString)))),
          "stimulusFile" -> stimulus.toString
        )
      case GenerateOutcome.Infeasible     => ujson.Obj("status" -> "infeasible")
      case GenerateOutcome.Unknown(d)     => ujson.Obj("status" -> "unknown", "detail" -> d)
