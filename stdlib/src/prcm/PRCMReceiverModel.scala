// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import me.jiuyang.smtlib.SMTQuery
import me.jiuyang.smtlib.default.{*, given}

private[prcm] case class PRCMReceiverFrame(frame: PRCMFrame, receiver: Int, feedback: Boolean)

private[prcm] class PRCMReceiverModel(stages: Int):
  require(stages > 0)
  val initial  =
    PRCMReceiverFrame(PRCMFrame(PRCMPhase.Init, PRCMTarget.Off, PRCMFeedback(false, true, true, true)), 0, true)
  val requests = PRCMTarget.values.toVector.map(Some(_)) :+ None

  // Sample before the management edge; apply asynchronous assertion again after the phase changes.
  def successors(
    state:          PRCMReceiverFrame,
    request:        Option[PRCMTarget],
    serviceFault:   Boolean = false,
    arbitraryPower: Boolean = false
  ): Vector[PRCMReceiverFrame] =
    val target   = request.getOrElse(state.frame.target)
    val advanced = state.frame.copy(
      target = target,
      phase = PRCMSequence.step(state.frame.phase, target, state.frame.feedback, serviceFault)
    )
    val old      = PRCMSequence.control(state.frame.phase)
    val now      = PRCMSequence.control(advanced.phase)
    val receiver =
      if now.reset then 0
      else if old.clock && !old.reset && state.receiver < stages then state.receiver + 1
      else state.receiver
    val sampled  = advanced.copy(feedback = advanced.feedback.copy(reset = state.feedback))
    sampled.responses
      .filter(_.feedback.reset == state.feedback)
      .flatMap: frame =>
        val powers = if arbitraryPower then Vector(false, true) else Vector(frame.feedback.power)
        powers.map(power =>
          PRCMReceiverFrame(
            frame.copy(feedback = frame.feedback.copy(power = power)),
            receiver,
            state.receiver != stages
          )
        )
      .distinct

  val reachable: Vector[PRCMReceiverFrame] = PRCMGraph.reachable(initial)(s => requests.flatMap(successors(s, _)))

  private val resetStates = (0 until 16)
    .filter(i => (i & 2) != 0)
    .map: bits =>
      PRCMReceiverFrame(PRCMFrame(PRCMPhase.Init, PRCMTarget.Off, PRCMFeedback.fromBits(bits)), 0, true)

  def faultSuccessors(state: PRCMReceiverFrame): Vector[PRCMReceiverFrame] =
    (requests.flatMap(request =>
      Vector(false, true).flatMap(fault => successors(state, request, fault, true))
    ) ++ resetStates).distinct

  lazy val faultReachable: Vector[PRCMReceiverFrame] = PRCMGraph.reachable(initial)(faultSuccessors)

  def released(state: PRCMReceiverFrame): Boolean =
    PRCMSequence.complete(state.frame.phase, PRCMTarget.Off, state.frame.feedback) ||
      PRCMSequence.complete(state.frame.phase, PRCMTarget.Reset, state.frame.feedback) ||
      (state.frame.phase == PRCMPhase.FaultOff && state.frame.feedback.bits == 14)

  def faultRecovery: Option[(Vector[PRCMReceiverFrame], Vector[PRCMReceiverFrame])] =
    offCycle(false)(_.frame.complete(Some(PRCMTarget.Off)))

  def faultRelease: Option[(Vector[PRCMReceiverFrame], Vector[PRCMReceiverFrame])] = offCycle(true)(released)

  def faultSafety(violation: Boolean): SMTQuery = SMTQuery.build:
    val fields   = Seq(
      "fault",
      "next_fault",
      "detected",
      "power",
      "next_power",
      "clock",
      "next_clock",
      "next_reset",
      "next_isolation",
      "power_good",
      "reset_feedback",
      "isolation_feedback",
      "idle",
      "target_off",
      "next_off",
      "receiver_asserted"
    )
    val signals  = fields.map(name => name -> smtValue(Bool, name)).toMap
    val row      = smtValue(SInt, "transition")
    val rows     = for
      state   <- faultReachable
      request <- requests
      fault   <- Vector(false, true)
    yield
      val before = state.frame
      val target = request.getOrElse(before.target)
      val next   = PRCMSequence.step(before.phase, target, before.feedback, fault)
      val a      = PRCMSequence.control(before.phase)
      val b      = PRCMSequence.control(next)
      Seq(
        PRCMSequence.fault(before.phase),
        PRCMSequence.fault(next),
        (fault && !PRCMSequence.fault(before.phase)) || PRCMSequence.powerLost(before.phase, before.feedback.power),
        a.power,
        b.power,
        a.clock,
        b.clock,
        b.reset,
        b.isolation,
        before.feedback.power,
        before.feedback.reset,
        before.feedback.isolation,
        before.feedback.idle,
        target == PRCMTarget.Off,
        next == PRCMPhase.Off,
        state.receiver == 0
      )
    smtAssert((row >= 0.S) & (row < rows.size.S))
    rows.zipWithIndex.foreach: (values, index) =>
      val assignment =
        fields.zip(values).map((name, value) => if value then signals(name) else !signals(name)).reduce(_ & _)
      smtAssert((row === index.S) ==> assignment)
    val rules    = Seq(
      "fault_protection"    -> (signals("next_fault") ==>
        (signals("next_reset") & !signals("next_clock") & signals("next_isolation"))),
      "fault_detection"     -> (signals("detected") ==> signals("next_fault")),
      "power_release"       -> ((signals("power") & !signals("next_power")) ==>
        (signals("reset_feedback") & signals("isolation_feedback") & !signals("clock"))),
      "fault_power_release" -> ((signals("fault") & signals("power") & !signals("next_power")) ==> signals("idle")),
      "fault_exit"          -> ((signals("fault") & !signals("next_fault")) ==>
        (signals("target_off") & signals("next_off") & !signals("power_good") &
          signals("reset_feedback") & signals("isolation_feedback"))),
      "receiver_protection" -> ((!signals("clock") | !signals("power")) ==> signals("receiver_asserted"))
    )
    val failures = rules.map: (name, rule) =>
      val flag = smtValue(Bool, s"violation_$name")
      smtAssert(smtEq(flag, !rule))
      flag
    if violation then smtAssert(failures.reduce(_ | _))

  private def offCycle(allowServiceFault: Boolean)(complete: PRCMReceiverFrame => Boolean)
    : Option[(Vector[PRCMReceiverFrame], Vector[PRCMReceiverFrame])] =
    val index    = faultReachable.zipWithIndex.toMap
    val all      = faultReachable.indices.toSet
    val faults   = if allowServiceFault then Vector(false, true) else Vector(false)
    val edges    =
      faultReachable.map(s => faults.flatMap(fault => successors(s, Some(PRCMTarget.Off), fault)).map(index).distinct)
    val active   = all.filter(i => !complete(faultReachable(i)))
    val fairness = Vector(0, 2, 3).map: bit =>
      all.filter: i =>
        val s = faultReachable(i).frame
        val c = PRCMSequence.control(s.phase)
        ((s.feedback.bits ^ PRCMFeedback(c.power, c.reset, c.isolation, c.quiesce).bits) & (1 << bit)) == 0
    PRCMGraph
      .fairCycle(edges, active, fairness)
      .map: loop =>
        val reachEdges = faultReachable.map(s => faultSuccessors(s).map(index))
        (PRCMGraph.path(reachEdges, 0, loop.head, all).map(faultReachable), loop.map(faultReachable))

  def progress: Option[(PRCMTarget, Vector[PRCMReceiverFrame], Vector[PRCMReceiverFrame])] =
    val index      = reachable.zipWithIndex.toMap
    val all        = reachable.indices.toSet
    val reachEdges = reachable.map(s => requests.flatMap(successors(s, _)).map(index).distinct)
    PRCMTarget.values.iterator
      .flatMap: target =>
        val edges    = reachable.map(s => successors(s, Some(target)).map(index))
        val active   = all.filter(i => !reachable(i).frame.complete(Some(target)))
        val fairness = Vector(0, 2, 3).map: bit =>
          all.filter: i =>
            val s = reachable(i).frame
            val c = PRCMSequence.control(s.phase)
            ((s.feedback.bits ^ PRCMFeedback(c.power, c.reset, c.isolation, c.quiesce).bits) & (1 << bit)) == 0
        PRCMGraph
          .fairCycle(edges, active, fairness)
          .map: loop =>
            (target, PRCMGraph.path(reachEdges, 0, loop.head, all).map(reachable), loop.map(reachable))
      .nextOption()

  def refinement(violation: Boolean): SMTQuery = SMTQuery.build:
    val edges        = reachable.flatMap(s => requests.flatMap(r => successors(s, r).map(t => (s, r, t))))
    val edge         = smtValue(SInt, "edge")
    val beforeReset  = smtValue(Bool, "before_reset_feedback")
    val afterReset   = smtValue(Bool, "after_reset_feedback")
    val resetRequest = smtValue(Bool, "reset_request")
    val clock        = smtValue(Bool, "clock_request")
    val power        = smtValue(Bool, "power_request")
    val receiver     = smtValue(Bool, "receiver_asserted")
    val projected    = smtValue(Bool, "model_step")
    smtAssert((edge >= 0.S) & (edge < edges.size.S))
    edges.zipWithIndex.foreach: (entry, index) =>
      val (s, r, t) = entry
      val control   = PRCMSequence.control(t.frame.phase)
      val expected  = Seq(
        beforeReset  -> s.frame.feedback.reset,
        afterReset   -> t.frame.feedback.reset,
        resetRequest -> control.reset,
        clock        -> control.clock,
        power        -> control.power,
        receiver     -> (t.receiver == 0),
        projected    -> s.frame.advance(r).responses.contains(t.frame)
      )
      smtAssert((edge === index.S) ==> expected.map((symbol, value) => if value then symbol else !symbol).reduce(_ & _))
    if violation then
      smtAssert(
        !projected | (!(beforeReset === afterReset) & !(afterReset === resetRequest)) |
          ((!clock | !power) & !receiver)
      )

  def prefixInduction: SMTQuery = SMTQuery.build:
    val count     = smtValue(SInt, "released_stages")
    val nextCount = smtValue(SInt, "next_released_stages")
    val oldReset  = smtValue(Bool, "reset_before_edge")
    val reset     = smtValue(Bool, "reset_after_edge")
    val clock     = smtValue(Bool, "clock_enabled")
    smtAssert((count >= 0.S) & (count <= stages.S))
    smtAssert(oldReset ==> (count === 0.S))
    val increment = smtIte(count < stages.S)(count + 1.S)(count)
    smtAssert(nextCount === smtIte(reset)(0.S)(smtIte(clock & !oldReset)(increment)(count)))
    val nextBits  = (0 until stages).map: index =>
      val previous = if index == 0 then true.B else count > (index - 1).S
      !reset & smtIte(clock & !oldReset)(previous)(count > index.S)
    smtAssert(nextBits.zipWithIndex.map((bit, index) => !(bit === (nextCount > index.S))).reduce(_ | _))
