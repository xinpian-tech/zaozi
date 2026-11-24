import me.jiuyang.stdlib.*
import me.jiuyang.utlib.*

object Generated extends UTExperiment:
  def run(outDir: os.Path): ujson.Value =
    val base  = os.Path("/root/yjh-workspace/rvprobe-workspace/zaozi/stdlib/tests/resources/haven")
    val files = Seq("i2c_master_top", "i2c_master_byte_ctrl", "i2c_master_bit_ctrl").map(n => base / s"$n.v")
    val ip    = SvImport.toHw(files, outDir / "imported", include = Some(base))

    // Bulk: one intent per register address, address pinned and the data byte left to the simulator.
    val regs = (0 to 7).map { a =>
      val param  = HavenI2cRegParameter(a)
      val model  = FormalUT.lowerGenerator(HavenI2cRegUT, param, outDir / s"reg$a")
      val merged = SvImport.mergeForBmc(model.hw, ip)
      FormalUT.generate(model.copy(hw = merged), bound = 3) match
        case GenerateOutcome.Generated(tr) =>
          AbstractStimulus.fromTrace(tr, UTGenerator(HavenI2cRegUT, param, outDir / s"reg$a").abi.spec)
        case other => throw RuntimeException(s"reg$a: $other")
    }
    val regSpec = UTGenerator(HavenI2cRegUT, HavenI2cRegParameter(0), outDir).abi.spec
    UvmSequence(
      "rvprobe_i2c_bulk_seq", "i2c_master_top_seq_item",
      pinned = Some(Set("wb_adr_i", "wb_we_i", "arst_i")), repeatPerBeat = 600
    ).write(UvmSequence.concat(regSpec, regs), outDir / "rvprobe_i2c_bulk_seq.sv")

    // Directed: the three residual-class witnesses, replayed exactly.
    val solved = I2cTarget.values.toSeq.map { t =>
      val param  = HavenI2cTargetParameter(t)
      val model  = FormalUT.lowerGenerator(HavenI2cTargetUT, param, outDir / t.toString)
      val merged = SvImport.mergeForBmc(model.hw, ip)
      val bound  = t match
        case I2cTarget.ArbitrationLost => 20
        case I2cTarget.FullTransfer    => 18
        case _                         => 12
      FormalUT.generate(model.copy(hw = merged), bound) match
        case GenerateOutcome.Generated(tr) =>
          AbstractStimulus.fromTrace(tr, UTGenerator(HavenI2cTargetUT, param, outDir / t.toString).abi.spec)
        case other => throw RuntimeException(s"$t: $other")
    }
    val tSpec = UTGenerator(HavenI2cTargetUT, HavenI2cTargetParameter(I2cTarget.ResetPulse), outDir).abi.spec
    // A witness runs to the full bound, so beats after the matched scenario are unconstrained — and the solver
    // fills them adversarially, here by re-asserting reset one cycle after the transfer is started. Keep each
    // witness only up to its last driven write.
    val trimmed = solved.map { st =>
      val last = st.beats.lastIndexWhere(_.values.get("wb_we_i").contains(BigInt(1)))
      if last < 0 then st else AbstractStimulus(st.spec, st.beats.take(last + 1))
    }
    val all   = UvmSequence.concat(tSpec, trimmed)
    // Only the four fields HAVEN's seq_item carries; the bus lines its BFM owns cannot be replayed through it.
    UvmSequence(
      "rvprobe_i2c_seq", "i2c_master_top_seq_item",
      pinned = Some(Set("wb_adr_i", "wb_dat_i", "wb_we_i", "arst_i")), repeatPerBeat = 1
    ).write(all, outDir / "rvprobe_i2c_seq.sv")

    // A transfer takes hundreds of cycles once started; polling the status register lets it run while keeping the
    // bus master busy with harmless reads. One beat, repeated.
    val pollParam = HavenI2cRegParameter(4, isWrite = false)
    val pollModel = FormalUT.lowerGenerator(HavenI2cRegUT, pollParam, outDir / "poll")
    val pollSpec  = UTGenerator(HavenI2cRegUT, pollParam, outDir / "poll").abi.spec
    val pollWit   = FormalUT.generate(pollModel.copy(hw = SvImport.mergeForBmc(pollModel.hw, ip)), bound = 3) match
      case GenerateOutcome.Generated(tr) => AbstractStimulus.fromTrace(tr, pollSpec)
      case other                         => throw RuntimeException(s"poll: $other")
    UvmSequence(
      "rvprobe_i2c_poll_seq", "i2c_master_top_seq_item",
      pinned = Some(Set("wb_adr_i", "wb_we_i", "arst_i")), repeatPerBeat = 4000
    ).write(pollWit, outDir / "rvprobe_i2c_poll_seq.sv")

    ujson.Obj("status" -> "generated", "bulkBeats" -> regs.map(_.cycles).sum, "directedBeats" -> all.cycles)
