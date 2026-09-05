// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
//
// The sdram arm: five flows on the OpenCores SDRAM controller (HAVEN's sdrc_top), solved on JasperGold and rendered
// as one UVM sequence for HAVEN's unmodified testbench (experiments/haven_tb/sdram/). A held Wishbone request in
// the witness is one transaction for the driver, and an idle stretch is a wait -- the strobe-aware codec.
// Installed into the experiments slot by ut_harness.py:
//   ZAOZI_EDA_SHELL=… ut_harness.py experiments/SdramFlowDriver.scala --out out/experiments/sdram-flow
import me.jiuyang.stdlib.*
import me.jiuyang.utlib.*

object Generated extends UTExperiment:
  def run(outDir: os.Path): ujson.Value =
    require(JasperGold.available, "the sdram flows need JasperGold (set ZAOZI_EDA_SHELL)")
    val rtlDir = os.Path("/root/yjh-workspace/rvprobe-workspace/zaozi/stdlib/tests/resources/haven/sdram")
    val files  = Seq(
      "sdrc_define", "async_fifo", "sync_fifo", "sdrc_bank_fsm", "sdrc_bank_ctl", "sdrc_bs_convert", "sdrc_req_gen",
      "sdrc_xfr_ctl", "sdrc_core", "wb2sdrc", "sdrc_top_split"
    ).map(n => rtlDir / s"$n.v")

    val rows    = collection.mutable.ArrayBuffer.empty[ujson.Obj]
    val flows   = Seq("init", "write", "read", "precharge", "refresh", "wrrd", "rdwr", "powerdown")
    val stimuli = flows.map { flow =>
      val param = HavenSdramFlowParameter(flow)
      val dir   = outDir / flow
      val spec  = UTGenerator(HavenSdramFlowUT, param, dir).abi.spec
      val t0    = System.currentTimeMillis()
      val out   = JasperGold.generate(JasperGold.lower(HavenSdramFlowUT, param, dir, files, include = Some(rtlDir)), dir / "jg", timeLimit = "900s")
      val ms    = System.currentTimeMillis() - t0
      out match
        case GenerateOutcome.Generated(tr) =>
          val st = AbstractStimulus.fromTrace(tr, spec)
          rows += ujson.Obj("flow" -> flow, "engine" -> "jaspergold", "ms" -> ms, "cycles" -> tr.cycles,
                            "requests" -> st.beats.count(_.values("wb_stb_i") == 1))
          st
        case other => throw RuntimeException(s"$flow: $other after ${ms}ms")
    }
    val spec = UTGenerator(HavenSdramFlowUT, HavenSdramFlowParameter("init"), outDir / "init").abi.spec
    val all  = UvmSequence.concat(spec, stimuli)
    val sv   = UvmSequence(
      "rvprobe_sdram_flow_seq", "sdrc_top_seq_item",
      pinned = Some(Set("wb_addr_i", "wb_dat_i", "wb_sel_i", "wb_we_i", "wb_cti_i", "cfg_sdr_en")),
      strobe = Some("wb_stb_i"), periodNs = 10
    ).write(all, outDir / "rvprobe_sdram_flow_seq.sv")
    // Volume: the write and read flows with only their structure pinned -- direction, byte enables, cycle type,
    // enable -- and address and data left to the simulator, five hundred transactions per solved request. The
    // ALU's fill, on a design whose toggle coverage is where eighteen random sequences beat five solved ones.
    val bulk = UvmSequence.concat(spec, Seq(stimuli(flows.indexOf("write")), stimuli(flows.indexOf("read"))))
    val bulkSv = UvmSequence(
      "rvprobe_sdram_bulk_seq", "sdrc_top_seq_item",
      pinned = Some(Set("wb_sel_i", "wb_we_i", "wb_cti_i", "cfg_sdr_en")), repeatPerBeat = 500,
      strobe = Some("wb_stb_i"), periodNs = 10
    ).write(bulk, outDir / "rvprobe_sdram_bulk_seq.sv")
    ujson.Obj("status" -> "generated", "beats" -> all.cycles, "sequenceFile" -> sv.toString, "bulkSequenceFile" -> bulkSv.toString, "flows" -> ujson.Arr(rows.toSeq*))
