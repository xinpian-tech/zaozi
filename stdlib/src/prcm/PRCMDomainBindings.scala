// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import me.jiuyang.smtlib.SMTQuery
import org.llvm.mlir.scalalib.capi.ir.Value

private[prcm] object PRCMDomainBindings:
  def queries(ir: String, metadata: ujson.Value, stages: Int): (SMTQuery, SMTQuery, SMTQuery) =
    PRCMRelation.use(ir, metadata): relation =>
      import relation.*
      def mismatch(name: String, expected: Value): Value = not(equal(bit(name), expected))
      val feasible = query()
      val state = metadata("states").arr.toVector
      state
        .filter(_("kind").str == "seq.firreg")
        .foreach: s =>
          require(s("clockEdge").num == 0 && s("resetPolarity").num == 1 && s("resetType").num == 1)
      val phaseState = state.filter(s => s("kind").str == "seq.firreg" && s("name").str == "phase")
      val gates = state.filter(_("kind").str == "llhd.sig")
      val receivers =
        state.filter(s => s("kind").str == "seq.firreg" && s("name").str == "receiver/synchronizationStages")
      val feedbacks = state.filter(s => s("kind").str == "seq.firreg" && s("name").str == "resetFeedback")
      require(phaseState.size == 1 && gates.size == 1 && receivers.size == stages && feedbacks.size == 2)
      require(state.size == stages + 4, "unexpected domain state")
      val phaseName = phaseState.head("symbol").str
      val gateName = gates.head("symbol").str
      val receiverNames = receivers.map(_("symbol").str)
      val feedbackNames = feedbacks.map(_("symbol").str)
      val observed = feedbackNames.last
      val next = for
        phase    <- PRCMPhase.values.toVector
        target   <- PRCMTarget.values.toVector
        feedback <- (0 until 16).toVector
        fault    <- Vector(false, true)
      yield
        val f        = PRCMFeedback.fromBits(feedback)
        val selected = and(
          select(phaseName, phase.ordinal, 4),
          select("target", target.ordinal, 2),
          equal(bit("powerGood"), bool(f.power)),
          equal(bit(observed), bool(f.reset)),
          equal(bit("isolationActive"), bool(f.isolation)),
          equal(bit("idle"), bool(f.idle)),
          equal(bit("serviceFault"), bool(fault))
        )
        and(selected, not(select(phaseName + "_d", PRCMSequence.step(phase, target, f, fault).ordinal, 4)))
      val outputs = for
        phase    <- PRCMPhase.values.toVector
        feedback <- (0 until 16).toVector
      yield
        val f        = PRCMFeedback.fromBits(feedback)
        val c        = PRCMSequence.control(phase)
        val selected = and(
          select(phaseName, phase.ordinal, 4),
          equal(bit("powerGood"), bool(f.power)),
          equal(bit(observed), bool(f.reset)),
          equal(bit("isolationActive"), bool(f.isolation)),
          equal(bit("idle"), bool(f.idle))
        )
        val ready    = PRCMTarget.values.map(t => PRCMSequence.complete(phase, t, f))
        val expected = Seq(
          "powerRequest"     -> c.power,
          "isolationRequest" -> c.isolation,
          "quiesceRequest"   -> c.quiesce,
          "fault"            -> PRCMSequence.fault(phase),
          "powerLost"        -> PRCMSequence.powerLost(phase, f.power),
          "readyOff"         -> ready(0),
          "readyReset"       -> ready(1),
          "readyRun"         -> ready(2),
          "released"         -> (ready(0) || ready(1) || (PRCMSequence.fault(
            phase
          ) && !c.power && !f.power && f.reset && f.isolation && f.idle))
        )
        and(
          selected,
          or(
            expected.map((name, value) => mismatch(name, bool(value))) ++
              receiverNames.map(name => mismatch(name + "_reset", and(bit("coldResetN"), bool(!c.reset)))) :+
              and(bit(gateName + "_enable"), mismatch(gateName + "_d", bool(c.clock)))
          )
        )
      val wires = Seq(
        phaseName + "_clock"      -> "clock",
        phaseName + "_reset"      -> "coldResetN",
        "domainResetN"            -> receiverNames.last,
        feedbackNames.last + "_d" -> feedbackNames.head
      ) ++
        receiverNames.map(name => name + "_clock" -> "domainClock") ++
        receiverNames.tail.zip(receiverNames).map((name, previous) => name + "_d" -> previous) ++
        feedbackNames.flatMap(name => Seq(name + "_clock" -> "clock", name + "_reset" -> "coldResetN"))
      val wiring = wires.map((a, b) => not(equal(values(a), values(b)))) ++
        Seq(
          not(select(phaseName + "_reset_value", 0, 4)),
          not(select(receiverNames.head + "_d", 1)),
          mismatch(feedbackNames.head + "_d", not(bit(receiverNames.last))),
          mismatch(gateName + "_enable", not(bit("clock"))),
          mismatch("domainClock", and(bit("clock"), bit(gateName)))
        ) ++
        receiverNames.map(name => not(select(name + "_reset_value", 0))) ++
        feedbackNames.map(name => not(select(name + "_reset_value", 1)))
      val bindings = query(Some(or(next ++ outputs ++ wiring)))
      val resetState = state
        .filter(_("kind").str == "seq.firreg")
        .map: register =>
          val name = register("symbol").str
          equal(values(name), values(name + "_reset_value"))
      val unsafeReset = or(
        Seq(
          bit("powerRequest"),
          not(bit("isolationRequest")),
          not(bit("quiesceRequest")),
          bit("domainResetN"),
          bit("readyOff"),
          bit("readyReset"),
          bit("readyRun")
        )
      )
      val resetViolation = and((Seq(not(bit("coldResetN")), unsafeReset) ++ resetState)*)
      (feasible, bindings, query(Some(resetViolation)))
