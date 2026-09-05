// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
//
// The untyped arm of the ablation. This file is fixed and authored by us, not by the model: the model's artifact is
// the `spec.json` this reads, so nothing the model produces passes through a type checker. A malformed field, an
// opcode out of range, a missing key -- all of it surfaces here as a runtime exception, or later as a VCS error, which
// is precisely the contrast being measured against the typed arm where the same mistakes are compile errors with a
// located message.
//
// Installed into the experiments slot by ablation.py, exactly as a model-written file would be.
import me.jiuyang.stdlib.*
import me.jiuyang.utlib.*

object Generated extends UTExperiment:
  /** Accept a number or a "0x…" string, because a model writes both; anything else is a runtime error. */
  private def num(v: ujson.Value): Long = v match
    case ujson.Num(n) => n.toLong
    case ujson.Str(s) =>
      val t = s.trim.toLowerCase.stripSuffix("l")
      if t.startsWith("0x") then java.lang.Long.parseLong(t.drop(2), 16) else java.lang.Long.parseLong(t)
    case other        => throw RuntimeException(s"expected a number, got $other")

  def run(outDir: os.Path): ujson.Value =
    val base = os.Path("/root/yjh-workspace/rvprobe-workspace/zaozi/stdlib/tests/resources/haven")
    val ip   = SvImport.toHw(Seq(base / "alu_top.v"), outDir / "imported")
    val spec = ujson.read(os.read(outDir / "spec.json"))

    val (stimuli, abi) = spec("form").str match
      case "plan" =>
        // The model supplies the operands; the solver only schedules the handshake around them.
        val cases = spec("cases").arr.toSeq.map { c =>
          (c("label").str, num(c("op")).toInt, num(c("a")), num(c("b")))
        }
        val out   = cases.map { (label, op, a, b) =>
          val param  = HavenAluFpParameter(op, a, b)
          val model  = FormalUT.lowerGenerator(HavenAluFpUT, param, outDir / label)
          val merged = SvImport.mergeForBmc(model.hw, ip)
          FormalUT.generate(model.copy(hw = merged), bound = 2) match
            case GenerateOutcome.Generated(t) =>
              AbstractStimulus.fromTrace(t, UTGenerator(HavenAluFpUT, param, outDir / label).abi.spec)
            case other                        => throw RuntimeException(s"$label: $other")
        }
        (out, UTGenerator(HavenAluFpUT, HavenAluFpParameter(0, 0L, 0L), outDir).abi.spec)

      case "goal" =>
        // The model supplies a destination over the DUT's outputs; the solver finds the operands.
        val goals = spec("goals").arr.toSeq.map { g =>
          HavenAluGoalParameter(
            label = g("label").str,
            flagsMask = g.obj.get("flagsMask").map(num(_).toInt).getOrElse(0),
            flagsValue = g.obj.get("flagsValue").map(num(_).toInt).getOrElse(0),
            opIs = g.obj.get("opIs").map(num(_).toInt),
            resultIs = g.obj.get("resultIs").map(num)
          )
        }
        val out   = goals.map { param =>
          val model  = FormalUT.lowerGenerator(HavenAluGoalUT, param, outDir / param.label)
          val merged = SvImport.mergeForBmc(model.hw, ip)
          FormalUT.generate(model.copy(hw = merged), bound = 12) match
            case GenerateOutcome.Generated(t) =>
              val st = AbstractStimulus.fromTrace(t, UTGenerator(HavenAluGoalUT, param, outDir / param.label).abi.spec)
              // The opcode is tied into the DUT rather than driven, so the witness's `op` port is free and must be
              // overwritten with the tied constant for the replay to run the operation that was actually solved.
              val tied = param.opIs.fold(st)(v =>
                AbstractStimulus(st.spec, st.beats.map(b => b.copy(values = b.values.updated("op", BigInt(v)))))
              )
              // A goal is solved at bound 12 to let the FP pipeline drain, so most of the witness is unconstrained
              // tail. Keep up to the last launch plus the pipeline's latency; without this the sequence is four inert
              // beats for every meaningful one and is not comparable with the plan arm's bound-2 witnesses.
              tied.trimAfterStrobe(drain = 8)("start")
            case other                        => throw RuntimeException(s"${param.label}: $other")
        }
        (out, UTGenerator(HavenAluGoalUT, HavenAluGoalParameter("probe"), outDir).abi.spec)

      case other  => throw RuntimeException(s"unknown form '$other'")

    val all = UvmSequence.concat(abi, stimuli)
    val sv  = UvmSequence("rvprobe_cell_seq", "alu_top_seq_item", pinned = Some(Set("op", "a", "b", "start")))
      .write(all, outDir / "rvprobe_cell_seq.sv")
    ujson.Obj("status" -> "generated", "beats" -> all.cycles, "sequenceFile" -> sv.toString)
