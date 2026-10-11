// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.stdlib.prcm

import utest.*

object PRCMSpec extends TestSuite:
  private def configuration(): PRCMParameter =
    val off      = PRCMMode("off", 0, PRCMTarget.Off)
    val consumer = PRCMManagedDomain("consumer", Seq(off, PRCMMode("run", 1, PRCMTarget.Run, Seq("clock"))), "off", 2)
    val provider = PRCMManagedDomain(
      "provider",
      Seq(off, PRCMMode("run", 1, PRCMTarget.Run)),
      "off",
      2
    )
    PRCMParameter(3, 32, 2, Seq(consumer, provider), Seq(PRCMService("clock", "provider")))

  val tests = Tests:
    test("PRCM cell configuration reaches internal instance choices"):
      import me.jiuyang.stdlib.clock.{*, given}
      val parameter = configuration().copy(
        gate = ClockGateCellParameter(true, Some(ClockGateTechnologyParameter("CUSTOM_ICG", "CK", "E", "Q"))),
        mux = ClockMuxCellParameter(Some(ClockMuxTechnologyParameter("CUSTOM_MUX", "I0", "I1", "S", "Z")))
      )
      val linked    = ClockCellTest.elaborate(PRCM, parameter)
      val directory = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", ""), os.pwd)
      assert(linked.contains("firrtl.instance_choice"))
      assert(linked.contains("ClockGateTechnology_"))
      assert(linked.contains("ClockMuxTechnology_"))
      assert(os.exists(directory / "ClockGatePositiveBehavior.sv"))
      assert(os.exists(directory / "ClockMuxBehavior.sv"))
      assert(!os.exists(directory / "ClockCells.sv"))
      val rtl       = ClockCellTest.rtl(linked)
      assert(rtl.contains("targets$ClockGateChoiceIO$Technology"))
      assert(rtl.contains("targets$ClockMuxChoiceIO$Technology"))
      assert(linked.contains("firrtl.int.verif.assert"))
      assert(linked.contains("firrtl.int.ltl.clocked_delay"))
      Seq(
        "power_off_requires_reset_isolation_and_stopped_clock",
        "fault_protects_domain",
        "released_service_returns",
        "service_0_1_permission_requires_live_provider",
        "domain_0_wait_service_reset",
        "domain_0_event_1_set_wins",
        "reset_released_after_stages",
        "gate_enabled_pulse",
        "domain_0_run_eventually_ready"
      ).foreach: label =>
        assert(linked.contains(label))
        assert(rtl.contains(label))
      assert(rtl.contains("assert property"))
      assert(upickle.default.read[PRCMParameter](upickle.default.write(parameter)) == parameter)
