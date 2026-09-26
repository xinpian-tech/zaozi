// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

import me.jiuyang.smtlib.parser.Z3Status
import utest.*

object PRCMSequenceSpec extends TestSuite:
  val tests = Tests:
    test("configuration witnesses satisfy service dependencies"):
      def modes(service: Seq[String]): Seq[PRCMMode] =
        Seq(PRCMMode("off", 0, PRCMTarget.Off), PRCMMode("run", 1, PRCMTarget.Run, service))
      val parameter = PRCMParameter(
        4,
        32,
        2,
        Seq(
          PRCMManagedDomain("provider", modes(Seq.empty), "off", 2),
          PRCMManagedDomain("consumer", modes(Seq("bus")), "off", 2)
        ),
        Seq(PRCMService("bus", "provider")),
        Some(
          PRCMChip(
            Seq(
              PRCMChipMode("active", 0, Map("provider" -> PRCMPolicy.Local, "consumer" -> PRCMPolicy.Fixed("run")))
            ),
            "active"
          )
        )
      )
      val proofs    = PRCMProof.configuration(parameter, 10000)
      val statuses  = proofs.map(_.result.status)
      assert(statuses.forall(_ == Z3Status.Sat))
      Seq("domain.consumer.mode.run", "chip.mode.active").foreach: name =>
        val witness = proofs.find(_.name == name).get.result.model.toMap
        assert(witness("mode_0") == BigInt(1))
        assert(witness("mode_1") == BigInt(1))

    test("domain action vectors match the reference"):
      val rows   = for
        phase  <- PRCMPhase.values.toSeq
        target <- PRCMTarget.values.toSeq
        bits   <- 0 until 16
        fault  <- 0 until 2
      yield
        val feedback = PRCMFeedback.fromBits(bits)
        val next     = PRCMSequence.step(phase, target, feedback, fault != 0)
        val control  = PRCMSequence.control(phase)
        val flags    = Seq(control.power, control.clock, control.reset, control.isolation, control.quiesce)
          .map(flag => if flag then "1" else "0")
          .mkString
        val lost     = if PRCMSequence.powerLost(phase, feedback.power) then 1 else 0
        val failed   = if PRCMSequence.fault(phase) then 1 else 0
        val done     = if PRCMSequence.complete(phase, target, feedback) then 1 else 0
        s"${phase.ordinal},${target.ordinal},$bits,$fault,${next.ordinal},$flags,$lost,$failed,$done\n"
      val digest = MessageDigest
        .getInstance("SHA-256")
        .digest(rows.mkString.getBytes(UTF_8))
        .map(byte => f"${byte & 255}%02x")
        .mkString
      // Action vectors emitted by qsoc c861a00ee0dfd1f3a7da63eee4d74896508c4c89.
      assert(digest == "6b692c7a3fc31d0b06c6765c88b4bf324cc0bf0b1f8f477685e53411d3ea8428")

    test("stable targets complete under fair feedback"):
      val model          = new PRCMDomainModel(PRCMTarget.values.toSeq)
      val counterexample = model.progress
      counterexample.foreach: (target, prefix, loop) =>
        assert(prefix.head == model.initial)
        assert(prefix.last == loop.head)
        assert(
          prefix
            .sliding(2)
            .filter(_.size == 2)
            .forall: pair =>
              model.requests.exists(request => pair.head.advance(request).responses.contains(pair.last))
        )
        assert(loop.head == loop.last)
        assert(loop.forall(frame => !frame.complete(Some(target))))
        assert(loop.sliding(2).forall(pair => pair.head.advance(Some(target)).responses.contains(pair.last)))
        assert((0 until 4).forall: bit =>
          loop.exists: frame =>
            val control = PRCMSequence.control(frame.phase)
            val desired = PRCMFeedback(control.power, control.reset, control.isolation, control.quiesce)
            ((frame.feedback.bits ^ desired.bits) & (1 << bit)) == 0)
      assert(counterexample.isEmpty)

    test("reachable domain transitions exclude safety violations"):
      val domain = PRCMManagedDomain(
        "core",
        Seq(
          PRCMMode("off", 0, PRCMTarget.Off),
          PRCMMode("reset", 1, PRCMTarget.Reset),
          PRCMMode("run", 2, PRCMTarget.Run)
        ),
        "off",
        2
      )
      val proof  = PRCMProof.domain(domain, 10000)
      val status = proof.checks.map(_.result.status)
      val passed = proof.passed
      assert(status == Seq(Z3Status.Sat, Z3Status.Unsat))
      assert(passed)

    test("shared service targets complete under fair feedback"):
      val model   = new PRCMServiceModel
      val status  = Seq(false, true).map(v => PRCMProof.serviceQuery(model, v).check(10000).status)
      assert(status == Seq(Z3Status.Sat, Z3Status.Unsat))
      val failure = model.progress
      failure.foreach: (targets, prefix, loop) =>
        assert(prefix.head == model.initial && prefix.last == loop.head)
        assert(
          prefix
            .sliding(2)
            .filter(_.size == 2)
            .forall: pair =>
              model.successors(pair.head.copy(other = false)).exists(_.copy(other = pair.last.other) == pair.last)
        )
        assert(loop.head == loop.last)
        assert(loop.forall(s => !s.complete(targets._1, targets._2)))
        assert(
          loop
            .sliding(2)
            .forall: pair =>
              (!pair.head.other || pair.last.other) &&
                pair.head
                  .advance(targets._1, targets._2, pair.head.other)
                  .responses
                  .exists(_.copy(other = pair.last.other) == pair.last)
        )
        assert((0 until 8).forall: bit =>
          loop.exists: state =>
            val frame   = if bit < 4 then state.provider else state.consumer
            val c       = PRCMSequence.control(frame.phase)
            val desired = PRCMFeedback(c.power, c.reset, c.isolation, c.quiesce)
            ((frame.feedback.bits ^ desired.bits) & (1 << (bit % 4))) == 0)
        assert(targets._2 != PRCMTarget.Run || loop.exists(_.other))
      assert(failure.isEmpty)

    test("lowered control circuits implement the action tables"):
      val checks = PRCMProof.controls(10000)
      val status = checks.map(_.result.status)
      assert(status == Seq.fill(4)(Seq(Z3Status.Sat, Z3Status.Unsat)).flatten)

    test("receiver feedback refines the domain without internal fairness assumptions"):
      Seq(1, 8).foreach: stages =>
        val model      = new PRCMReceiverModel(stages)
        val feasible   = model.refinement(false).check(10000).status
        val refinement = model.refinement(true).check(10000).status
        val induction  = model.prefixInduction.check(10000).status
        val progress   = model.progress
        assert(feasible == Z3Status.Sat)
        assert(refinement == Z3Status.Unsat)
        assert(induction == Z3Status.Unsat)
        progress.foreach: (target, prefix, loop) =>
          assert(prefix.head == model.initial && prefix.last == loop.head)
          assert(
            prefix
              .sliding(2)
              .filter(_.size == 2)
              .forall: pair =>
                model.requests.exists(request => model.successors(pair.head, request).contains(pair.last))
          )
          assert(loop.head == loop.last)
          assert(loop.forall(s => !s.frame.complete(Some(target))))
          assert(loop.sliding(2).forall(pair => model.successors(pair.head, Some(target)).contains(pair.last)))
          assert(Vector(0, 2, 3).forall: bit =>
            loop.exists: s =>
              val c       = PRCMSequence.control(s.frame.phase)
              val desired = PRCMFeedback(c.power, c.reset, c.isolation, c.quiesce)
              ((s.frame.feedback.bits ^ desired.bits) & (1 << bit)) == 0)
        assert(progress.isEmpty)

    test("fair cycles visit every response witness"):
      val edges    = Vector(Vector(1), Vector(2), Vector(0, 3), Vector(3))
      val active   = Set(0, 1, 2)
      val fairness = Vector(Set(0), Set(1), Set(2))
      val loop     = PRCMGraph.fairCycle(edges, active, fairness).get
      assert(loop.head == loop.last)
      assert(fairness.forall(group => loop.exists(group)))
      assert(loop.sliding(2).forall(pair => edges(pair.head).contains(pair.last)))
      assert(PRCMGraph.fairCycle(edges, active, fairness :+ Set(3)).isEmpty)

    test("faulted receivers release services and recover to Off"):
      Seq(1, 3).foreach: stages =>
        val model    = new PRCMReceiverModel(stages)
        val feasible = model.faultSafety(false).check(10000).status
        val safety   = model.faultSafety(true).check(10000)
        assert(feasible == Z3Status.Sat && safety.status == Z3Status.Unsat)
        val phases   = model.faultReachable.map(_.frame.phase).toSet
        assert(Set(PRCMPhase.FaultRelease, PRCMPhase.Fault, PRCMPhase.FaultOff, PRCMPhase.FaultPower).subsetOf(phases))
        val recovery = model.faultRecovery
        val release  = model.faultRelease
        Seq(recovery -> false, release -> true).foreach: (failure, allowFault) =>
          failure.foreach: (prefix, loop) =>
            assert(prefix.head == model.initial && prefix.last == loop.head)
            assert(
              prefix.sliding(2).filter(_.size == 2).forall(pair => model.faultSuccessors(pair.head).contains(pair.last))
            )
            assert(loop.head == loop.last)
            assert(loop.forall(s => if allowFault then !model.released(s) else !s.frame.complete(Some(PRCMTarget.Off))))
            assert(
              loop
                .sliding(2)
                .forall: pair =>
                  (if allowFault then Vector(false, true) else Vector(false)).exists(fault =>
                    model.successors(pair.head, Some(PRCMTarget.Off), fault).contains(pair.last)
                  )
            )
            assert(Vector(0, 2, 3).forall: bit =>
              loop.exists: s =>
                val c       = PRCMSequence.control(s.frame.phase)
                val desired = PRCMFeedback(c.power, c.reset, c.isolation, c.quiesce)
                ((s.frame.feedback.bits ^ desired.bits) & (1 << bit)) == 0)
        assert(recovery.isEmpty && release.isEmpty)

    test("composition preserves requests and completed provider states"):
      PRCMCompositionProof.queries.foreach: (name, expected, query) =>
        val status = query.check(10000).status
        assert(status == expected)
