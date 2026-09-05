// SPDX-License-Identifier: Apache-2.0

// Task: lower a supplied UT, handle every solver outcome, and export a UVM sequence.
// Given: a UT, parameters, RTL sources, a fresh output directory and codec settings.
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

  def exportSequence[
    PARAM <: Parameter,
    L <: LayerInterface[PARAM],
    I <: HWInterface[PARAM],
    P <: DVInterface[PARAM, L]
  ](
    dut: Generator[PARAM, L, I, P] & UT[PARAM, I],
    parameter: PARAM,
    rtl: Seq[os.Path],
    outDir: os.Path,
    timeLimit: String,
    sequenceName: String,
    itemType: String,
    pinnedFields: Set[String]
  ): Either[String, os.Path] =
    require(JasperGold.available, "JasperGold is required for this example")
    val generator = UTGenerator(dut, parameter, outDir)
    val spec = generator.abi.spec
    val driveNames = spec.drive.map(_.name).toSet
    require(pinnedFields.subsetOf(driveNames), "Pinned fields must be ABI drive ports")
    val model = JasperGold.lower(dut, parameter, outDir / "lowered", rtl)
    val outcome = JasperGold.generate(model, outDir / "solve", timeLimit = timeLimit)
    interpret(outcome, spec).map { stimulus =>
      // concat also accepts several stimuli with matching drive-port signatures.
      val stream = UvmSequence.concat(spec, Seq(stimulus))
      val codec = UvmSequence(sequenceName, itemType, pinned = Some(pinnedFields))
      codec.write(stream, outDir / "sequence.sv")
    }

  // Neither Right(sequencePath) nor Infeasible proves anything about a target that
  // was not encoded in the UT. Replay and dedicated properties are separate checks.
