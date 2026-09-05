import me.jiuyang.stdlib.*
import me.jiuyang.utlib.*

object Generated extends UTExperiment:
  def run(outDir: os.Path): ujson.Value =
    require(JasperGold.available, "the ALU residual loop requires JasperGold")
    val svFile = os.Path("stdlib/tests/resources/haven/alu_top.v", os.pwd)
    // (label, opcode, a, b) — each entry is one directed intent.
    val cases = Seq(
      ("f2i_shift_ge_32", 8, 0x3f000000L, 0x00000000L),
      ("not_default_fp_inactive", 9, 0x00000000L, 0x00000000L)
    )
    val rows = collection.mutable.ArrayBuffer.empty[ujson.Obj]
    val stimuli = cases.map { (label, op, a, b) =>
      val param = HavenAluFpParameter(op, a, b)
      val dir   = outDir / label
      val spec  = UTGenerator(HavenAluFpUT, param, dir).abi.spec
      val t0    = System.currentTimeMillis()
      val out   = JasperGold.generate(
        JasperGold.lower(HavenAluFpUT, param, dir, Seq(svFile)),
        dir / "jg",
        timeLimit = "120s"
      )
      val ms = System.currentTimeMillis() - t0
      out match
        case GenerateOutcome.Generated(trace) =>
          rows += ujson.Obj("label" -> label, "engine" -> "jaspergold", "ms" -> ms, "cycles" -> trace.cycles)
          AbstractStimulus.fromTrace(trace, spec)
        case other => throw RuntimeException(s"$label: $other after ${ms}ms")
    }
    val spec = UTGenerator(HavenAluFpUT, HavenAluFpParameter(0, 0L, 0L), outDir).abi.spec
    val all  = UvmSequence.concat(spec, stimuli)
    val sv   = UvmSequence(
      "rvprobe_llm_seq", "alu_top_seq_item",
      pinned = Some(Set("op", "a", "b", "start"))
    ).write(all, outDir / "rvprobe_llm_seq.sv")
    ujson.Obj(
      "status" -> "generated", "engine" -> "jaspergold", "beats" -> all.cycles,
      "sequenceFile" -> sv.toString, "intents" -> ujson.Arr(rows.toSeq*)
    )
