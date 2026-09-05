// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
//
// The can arm: the two flows HAVEN's generated bench can replay -- leaving reset and transmitting a frame -- solved
// on JasperGold with the engine playing the second node, and rendered as one UVM sequence for the bench's Wishbone
// driver (experiments/haven_tb/can/). The receive and bus-off flows are solved too, for the record: their witnesses
// drive the CAN bus, which the bench's reactive BFM owns, so they cannot be replayed through it.
// Installed into the experiments slot by ut_harness.py:
//   ZAOZI_EDA_SHELL=… ut_harness.py experiments/legacy/drivers/CanFlowDriver.scala --out out/experiments/can-flow
import me.jiuyang.stdlib.*
import me.jiuyang.utlib.*

object Generated extends UTExperiment:
  def run(outDir: os.Path): ujson.Value =
    require(JasperGold.available, "the can flows need JasperGold (set ZAOZI_EDA_SHELL)")
    val rtlDir = os.Path("experiments/fixtures/haven/can", os.pwd)
    val files  = Seq(
      "can_defines", "can_crc", "can_ibo", "can_acf", "can_register", "can_register_asyn", "can_register_asyn_syn",
      "can_register_syn", "can_fifo", "can_btl", "can_bsp", "can_registers", "can_top"
    ).map(n => rtlDir / s"$n.v")

    val rows = collection.mutable.ArrayBuffer.empty[ujson.Obj]
    def solve(flow: String): Option[AbstractStimulus] =
      val param = HavenCanFlowParameter(flow, partner = if flow == "transmit" then "ack_once" else "free")
      val dir   = outDir / flow
      val spec  = UTGenerator(HavenCanFlowUT, param, dir).abi.spec
      val t0    = System.currentTimeMillis()
      val out   = JasperGold.generate(JasperGold.lower(HavenCanFlowUT, param, dir, files, include = Some(rtlDir)), dir / "jg", timeLimit = if flow == "leave_reset" || flow == "sof" then "600s" else "300s")
      val ms    = System.currentTimeMillis() - t0
      out match
        case GenerateOutcome.Generated(tr) =>
          val st = AbstractStimulus.fromTrace(tr, spec)
          rows += ujson.Obj("flow" -> flow, "engine" -> "jaspergold", "ms" -> ms, "cycles" -> tr.cycles,
                            "requests" -> st.beats.count(_.values("wb_stb_i") == 1))
          Some(st)
        case other =>
          rows += ujson.Obj("flow" -> flow, "engine" -> "jaspergold", "ms" -> ms, "outcome" -> other.toString.take(200))
          None

    // Replayable through the bench's Wishbone driver: leaving reset and starting a frame. The acknowledged
    // transmission, a received frame and bus-off are solved for the record with a bounded time: each needs the
    // other node to act at frame level, and with that node reduced to what the engine drives on one wire, the
    // engine did not decide any of them in 900 s.
    val replayable = Seq("leave_reset", "sof").flatMap(solve)
    Seq("transmit", "receive", "busoff").foreach(solve)
    require(replayable.nonEmpty, "no replayable can flow solved")
    val spec = UTGenerator(HavenCanFlowUT, HavenCanFlowParameter("leave_reset"), outDir / "leave_reset").abi.spec
    val all  = UvmSequence.concat(spec, replayable)
    val sv   = UvmSequence(
      "rvprobe_can_flow_seq", "can_top_seq_item",
      pinned = Some(Set("wb_adr_i", "wb_dat_i", "wb_we_i")), strobe = Some("wb_stb_i"), periodNs = 10
    ).write(all, outDir / "rvprobe_can_flow_seq.sv")
    ujson.Obj("status" -> "generated", "beats" -> all.cycles, "sequenceFile" -> sv.toString, "flows" -> ujson.Arr(rows.toSeq*))
