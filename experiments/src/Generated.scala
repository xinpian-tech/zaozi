// Targets the FP corner cases the URG residual reports as uncovered: NaN, infinity, zero result,
// overflow. Each intent names a *value pattern on the operands* and lets the solver drive the pipeline.
import me.jiuyang.stdlib.*
import me.jiuyang.utlib.*

object Generated extends UTExperiment:
  def run(outDir: os.Path): ujson.Value =
    val svFile = os.Path("/root/yjh-workspace/rvprobe-workspace/zaozi/stdlib/tests/resources/haven/alu_top.v")
    val ip     = SvImport.toHw(Seq(svFile), outDir / "imported")

    // (label, opcode, a, b) — FP add/sub over corner-case operands.
    val cases = Seq(
      ("nan_a",      0, 0x7fc00000L, 0x3f800000L),
      ("inf_plus",   0, 0x7f800000L, 0x7f800000L),
      ("inf_minus",  1, 0x7f800000L, 0x7f800000L),
      ("zero_sum",   1, 0x3f800000L, 0x3f800000L),
      ("overflow",   0, 0x7f7fffffL, 0x7f7fffffL),
      ("denormal",   0, 0x00000001L, 0x80000001L)
    )

    val stimuli = cases.map { (label, op, a, b) =>
      val param  = HavenAluFpParameter(op, a, b)
      val model  = FormalUT.lowerGenerator(HavenAluFpUT, param, outDir / label)
      val merged = SvImport.mergeForBmc(model.hw, ip)
      FormalUT.generate(model.copy(hw = merged), bound = 2) match
        case GenerateOutcome.Generated(t) =>
          AbstractStimulus.fromTrace(t, UTGenerator(HavenAluFpUT, param, outDir / label).abi.spec)
        case other                        => throw RuntimeException(s"$label: $other")
    }

    val spec = UTGenerator(HavenAluFpUT, HavenAluFpParameter(0, 0L, 0L), outDir).abi.spec
    val all  = UvmSequence.concat(spec, stimuli)
    val sv   = UvmSequence("rvprobe_fp_seq", "alu_top_seq_item", pinned = Some(Set("op", "a", "b", "start")))
      .write(all, outDir / "rvprobe_fp_seq.sv")
    ujson.Obj("status" -> "generated", "beats" -> all.cycles, "sequenceFile" -> sv.toString)
