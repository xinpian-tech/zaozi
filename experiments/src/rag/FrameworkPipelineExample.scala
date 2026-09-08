// SPDX-License-Identifier: Apache-2.0

// Task: solve an already validated lowered UT, handle every outcome, and export a sequence.
// Given: trusted JgModel/ABI data, a fresh output directory and codec settings.
// Example solution: the caller supplies the design and constraints; no stimulus or
// reachability conclusion is embedded. This is runner-side usage, not fragment output.
import me.jiuyang.utlib.*
import me.jiuyang.zaozi.*

object FrameworkPipelineExample:
  def interpret(outcome: GenerateOutcome, spec: AbiSpec): Either[String, AbstractStimulus] =
    outcome match
      case GenerateOutcome.Generated(trace) =>
        Right(AbstractStimulus.fromTrace(trace, spec))
      case GenerateOutcome.Infeasible =>
        Left("No witness for the encoded intent under the selected backend's semantics.")
      case GenerateOutcome.Unknown(detail) =>
        Left(s"No conclusive solver result: $detail")

  // Model-authored Scala is compiled/lowered separately in isolation. It is never
  // loaded into this trusted solver process; the caller first validates DUT wiring.
  def exportSequence(
    model: JgModel,
    spec: AbiSpec,
    selectedLabel: String,
    outDir: os.Path,
    timeLimit: String,
    sequenceName: String,
    itemType: String
  ): Either[String, os.Path] =
    require(JasperGold.available, "JasperGold is required for this example")
    JasperGold.requireUnconstrainedUT(model)
    val selected = JasperGold.selectGoal(model, selectedLabel, outDir / "selected")
    val outcome = JasperGold.generate(selected, outDir / "solve", timeLimit = timeLimit)
    interpret(outcome, spec).map { stimulus =>
      val codec = UvmSequence(sequenceName, itemType)
      codec.write(stimulus, outDir / "sequence.sv")
    }

  // Neither Right(sequencePath) nor Infeasible proves anything about a target that
  // was not encoded in the UT. Replay and dedicated properties are separate checks.
