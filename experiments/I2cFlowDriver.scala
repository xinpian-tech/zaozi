// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
//
// The i2c transaction-flow arm: five command flows and two reset-midway intents, solved through the vendored
// OpenCores RTL and rendered as UVM sequences for HAVEN's unmodified i2c testbench (experiments/haven_tb/i2c/).
// Installed into the experiments slot by ut_harness.py: `ut_harness.py experiments/I2cFlowDriver.scala --out out/experiments/i2c-flow`.
import me.jiuyang.stdlib.*
import me.jiuyang.utlib.*

/** Five transaction-flow intents on the OpenCores I2C master, one per byte_ctrl command shape, solved through the
  * vendored RTL and rendered as one UVM sequence for HAVEN's unmodified i2c testbench.
  */
object Generated extends UTExperiment:
  def run(outDir: os.Path): ujson.Value =
    val base  = os.Path("/root/yjh-workspace/rvprobe-workspace/zaozi/stdlib/tests/resources/haven")
    val files = Seq("i2c_master_top", "i2c_master_byte_ctrl", "i2c_master_bit_ctrl").map(n => base / s"$n.v")
    val ip    = SvImport.toHw(files, outDir / "imported", include = Some(base))

    // CR bits: STA 0x80 | STO 0x40 | RD 0x20 | WR 0x10 | ACK 0x08. TXR carries the slave address (0x50 << 1) with
    // the R/W bit, or a data byte; the BFM answers 0x50, so address bytes are 0xA0 (write) / 0xA1 (read).
    val flows = Seq(
      HavenI2cFlowParameter("wr_sto", txr = 0xa0, cmd = 0xd0, gapStyle = "none", prer = 0), // IDLE→START→WRITE→ACK→STOP→IDLE
      HavenI2cFlowParameter("rd_sto", txr = 0xa1, cmd = 0xe8, gapStyle = "none", prer = 0), // IDLE→START→READ→ACK(NACK)→STOP→IDLE
      HavenI2cFlowParameter("wr",     txr = 0x5a, cmd = 0x10, gapStyle = "none", prer = 0), // IDLE→WRITE→ACK→IDLE (no START)
      HavenI2cFlowParameter("rd",     txr = 0x00, cmd = 0x28, gapStyle = "none", prer = 0), // IDLE→READ→ACK→IDLE
      HavenI2cFlowParameter("sto",    txr = 0x00, cmd = 0x40, gapStyle = "none")  // IDLE→STOP→IDLE
    )
    val bound = 12 // five acknowledged writes with a poll between each
    val polls = 60 // timing fill: status polls appended after the command so the transfer completes before the next flow
    // (prescaler 0 here: HAVEN's testbench puts a slave BFM on the bus rather than a loopback, and its flows complete)

    val rows    = collection.mutable.ArrayBuffer.empty[ujson.Obj]
    // The engine: JasperGold when reachable (the flow then proves its own completion — ~220 cycles at prescaler 2,
    // polls included in the witness), else circt-bmc on the structure alone with a timing fill of status polls.
    val jg = JasperGold.available
    val stimuli = flows.map { param0 =>
      val param = if jg then param0.copy(gapStyle = "repeat", prer = 2) else param0
      val dir   = outDir / param.label
      val spec  = UTGenerator(HavenI2cFlowUT, param, dir).abi.spec
      val t0    = System.currentTimeMillis()
      val out   =
        if jg then JasperGold.generate(JasperGold.lower(HavenI2cFlowUT, param, dir, files, include = Some(base)), dir / "jg")
        else
          val model = FormalUT.lowerGenerator(HavenI2cFlowUT, param, dir)
          FormalUT.generate(model.copy(hw = SvImport.mergeForBmc(model.hw, ip)), bound)
      val ms    = System.currentTimeMillis() - t0
      out match
        case GenerateOutcome.Generated(tr) =>
          val st    = AbstractStimulus.fromTrace(tr, spec)
          val we    = st.beats.map(_.values("wb_we_i"))
          val cmdAt = we.lastIndexWhere(_ == 1)
          val poll  = Beat(st.beats(cmdAt).values ++ Map("wb_adr_i" -> BigInt(4), "wb_dat_i" -> BigInt(0), "wb_we_i" -> BigInt(0), "arst_i" -> BigInt(1)))
          // JasperGold's witness runs to the observed completion and is replayed whole; circt-bmc's stops at the
          // command write and is filled with polls.
          val kept  = if jg then st else AbstractStimulus(st.spec, st.beats.take(cmdAt + 1) ++ Vector.fill(polls)(poll))
          rows += ujson.Obj("flow" -> param.label, "engine" -> (if jg then "jaspergold" else "circt-bmc"), "ms" -> ms,
                            "cycles" -> tr.cycles, "kept" -> kept.cycles, "cmd" -> param.cmd, "prer" -> param.prer)
          kept
        case other =>
          throw RuntimeException(s"${param.label}: $other after ${ms}ms")
    }
    val spec   = UTGenerator(HavenI2cFlowUT, flows.head, outDir / flows.head.label).abi.spec
    val pinned = Some(Set("wb_adr_i", "wb_dat_i", "wb_we_i", "arst_i"))
    val all    = UvmSequence.concat(spec, stimuli)
    val sv     = UvmSequence("rvprobe_i2c_flow_seq", "i2c_master_top_seq_item", pinned = pinned)
      .write(all, outDir / "rvprobe_i2c_flow_seq.sv")

    // Reset mid-transfer: one intent per command shape, solved once each; the witness fixes everything but WHEN the
    // reset lands, and that free timing is filled by replay — the same "solver supplies the structure, simulator
    // fills the free dimension" as the ALU's pinned-field randomization, over time instead of data. Prescaler 2
    // makes one bit phase three clocks, the Wishbone driver's cost per transaction, so a sweep over the poll count
    // steps through every phase of the transfer.
    val resets = Seq(
      HavenI2cFlowParameter("rst_wr", txr = 0xa0, cmd = 0xd0, resetMidway = true, maxWait = 12),
      HavenI2cFlowParameter("rst_rd", txr = 0xa1, cmd = 0xe8, resetMidway = true, maxWait = 12)
    )
    val sweep  = 60
    val rstStimuli = resets.flatMap { param =>
      val dir    = outDir / param.label
      val t0     = System.currentTimeMillis()
      val out    =
        if jg then JasperGold.generate(JasperGold.lower(HavenI2cFlowUT, param, dir, files, include = Some(base)), dir / "jg")
        else
          val model = FormalUT.lowerGenerator(HavenI2cFlowUT, param, dir)
          FormalUT.generate(model.copy(hw = SvImport.mergeForBmc(model.hw, ip)), bound = 28)
      val ms     = System.currentTimeMillis() - t0
      out match
        case GenerateOutcome.Generated(tr) =>
          val st   = AbstractStimulus.fromTrace(tr, UTGenerator(HavenI2cFlowUT, param, dir).abi.spec)
          val we   = st.beats.map(_.values("wb_we_i"))
          val arst = st.beats.map(_.values("arst_i"))
          val cmdAt = we.indices.filter(i => we(i) == 1).lastOption.getOrElse(throw RuntimeException(s"${param.label}: no write"))
          val rstAt = arst.indices.find(i => i > cmdAt && arst(i) == 0).getOrElse(throw RuntimeException(s"${param.label}: no reset beat"))
          val poll  = st.beats(cmdAt + 1) // the solver's own gap beat: a status poll with reset released
          val head  = st.beats.take(cmdAt + 1)
          val tail  = st.beats.slice(rstAt, (rstAt + 4).min(st.beats.length))
          rows += ujson.Obj("flow" -> param.label, "engine" -> (if jg then "jaspergold" else "circt-bmc"), "ms" -> ms, "cycles" -> tr.cycles, "solverGap" -> (rstAt - cmdAt - 1), "sweep" -> sweep)
          (0 until sweep).map(k => AbstractStimulus(st.spec, head ++ Vector.fill(k)(poll) ++ tail))
        case other => throw RuntimeException(s"${param.label}: $other after ${ms}ms")
    }
    val rstAll = UvmSequence.concat(spec, rstStimuli)
    val rstSv  = UvmSequence("rvprobe_i2c_rst_seq", "i2c_master_top_seq_item", pinned = pinned)
      .write(rstAll, outDir / "rvprobe_i2c_rst_seq.sv")
    ujson.Obj(
      "status" -> "generated", "beats" -> all.cycles, "sequenceFile" -> sv.toString,
      "resetBeats" -> rstAll.cycles, "resetSequenceFile" -> rstSv.toString, "flows" -> ujson.Arr(rows.toSeq*)
    )
