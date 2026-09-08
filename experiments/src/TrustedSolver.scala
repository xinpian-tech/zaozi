// SPDX-License-Identifier: Apache-2.0
import me.jiuyang.utlib.*

/** Trusted data-only execution. This JVM never includes model-authored classes. */
@main def solvePrepared(jobPath: String, outputPath: String): Unit =
  val job = ujson.read(os.read(os.Path(jobPath)))
  val out = os.Path(outputPath)
  val labels = job("labels").arr.map(_.str).toSeq
  val model = JgModel(os.Path(job("sv").str), job("top").str,
    job("rtl").arr.map(v => os.Path(v.str)).toSeq, labels.toSet,
    job("include").strOpt.map(os.Path(_)))
  val abi = AbiSpec.fromJson(ujson.write(job("abi")))
  JasperGold.requireUnconstrainedUT(model)
  val rows = labels.map { label =>
    val dir = out / label
    os.makeDir.all(dir)
    val checkpoint = dir / "goal.json"
    val cached = if os.exists(checkpoint) then scala.util.Try(ujson.read(os.read(checkpoint))).toOption else None
    val reuse = cached.filter { row => scala.util.Try {
      row("fingerprint").str == job("fingerprint").str && row("status").str == "generated" &&
        os.exists(os.Path(row("witnessFile").str)) &&
        sha(os.Path(row("witnessFile").str)) == row("witnessSha256").str &&
        os.exists(os.Path(row("stimulusFile").str)) &&
        sha(os.Path(row("stimulusFile").str)) == row("stimulusSha256").str
      }.getOrElse(false)
    }
    reuse.getOrElse {
      val began = System.currentTimeMillis()
      val event = ujson.Obj("id" -> java.util.UUID.randomUUID().toString, "phase" -> "goal-solve",
        "started_utc" -> java.time.Instant.now().toString, "status" -> "running", "label" -> label)
      def recordEvent(): Unit = os.write.append(dir / "events.jsonl", ujson.write(event) + "\n", createFolders = true)
      recordEvent()
      val row = ujson.Obj("label" -> label, "generationLabel" -> label,
        "utModule" -> job("module"), "utSourceSha256" -> job("sourceSha256"),
        "fingerprint" -> job("fingerprint"), "startedUtc" -> java.time.Instant.now().toString,
        "engine" -> "jaspergold")
      try
        val selected = JasperGold.selectGoal(model, label, dir / "selected")
        JasperGold.generate(selected, dir / "jg", job("timeLimit").str) match
          case GenerateOutcome.Generated(trace) =>
            val stimulus = AbstractStimulus.fromTrace(trace, abi)
            os.write.over(dir / "stimulus.json", ujson.write(ujson.Arr.from(stimulus.beats.map(b =>
              ujson.Obj.from(b.values.toSeq.map((name, value) => name -> ujson.Str(value.toString))))), indent = 2))
            val sequence = UvmSequence(job("sequenceName").str, job("itemType").str)
              .write(stimulus, dir / "sequence.sv")
            row("status") = "generated"
            row("cycles") = trace.cycles
            row("stimulusFile") = (dir / "stimulus.json").toString
            row("stimulusSha256") = sha(dir / "stimulus.json")
            row("sequenceFile") = sequence.toString
            row("witnessFile") = (dir / "jg" / "witness.vcd").toString
            row("witnessContract") = JasperGold.witnessContract
            row("witnessSha256") = sha(dir / "jg" / "witness.vcd")
          case GenerateOutcome.Infeasible => row("status") = "infeasible"
          case GenerateOutcome.Unknown(detail) =>
            row("status") = "unknown"
            row("detail") = detail
      catch
        case error: Exception =>
          row("status") = "error"
          row("detail") = error.toString
      row("ms") = (System.currentTimeMillis() - began).toDouble
      row("finishedUtc") = java.time.Instant.now().toString
      event("finished_utc") = row("finishedUtc")
      event("seconds") = row("ms").num / 1000
      event("status") = if row("status").str == "error" then "failed" else "ok"
      event("solver_status") = row("status")
      recordEvent()
      os.write.over(dir / "goal.tmp", ujson.write(row, indent = 2))
      os.move.over(dir / "goal.tmp", checkpoint)
      row
    }
  }
  val generated = rows.count(_("status").str == "generated")
  val status = if generated == rows.size then "generated" else if generated > 0 then "partial" else "no-witness"
  val report = ujson.Obj("status" -> status, "engine" -> "jaspergold", "utCount" -> 1,
    "utModule" -> job("module"), "environmentPolicy" -> "fixed-reset-no-model-assumptions-v1",
    "isolationPolicy" -> "bubblewrap-compile-lower-v1", "replayContract" -> "cycle-replay-v1",
    "beats" -> rows.filter(_("status").str == "generated").map(_("cycles").num.toInt).sum,
    "goals" -> ujson.Arr.from(rows), "proofObligations" -> job("proofObligations"))
  os.write.over(out / "report.json", ujson.write(report, indent = 2))

private def sha(path: os.Path): String =
  java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(os.read.bytes(path)))
