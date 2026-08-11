// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.zaozi.HWInterface

/** Outcome of checking a property against the constrained stimulus space.
  *
  * A property here ranges over the same per-cycle input symbols the stimulus solver uses, so a
  * violation is not an abstract fact: its model *is* a stimulus, and [[UTGenerator.check]]
  * replays it through the simulator before reporting — a counterexample that does not survive
  * concrete re-simulation is a framework bug, not a finding.
  */
sealed trait PropertyOutcome[I <: HWInterface[?]]

object PropertyOutcome:
  /** No stimulus admitted by the constraints violates the property. */
  final case class Proven[I <: HWInterface[?]]() extends PropertyOutcome[I]

  /** The constraints admit `stimulus`, which violates the property; `replay` is its concrete
    * simulation, run with tracing so the waveform is available for debugging.
    */
  final case class Falsified[I <: HWInterface[?]](
    stimulus: SolvedStimulus[I],
    replay:   RunResult)
      extends PropertyOutcome[I]

  /** The solver could not decide. */
  final case class Unknown[I <: HWInterface[?]](status: String) extends PropertyOutcome[I]
