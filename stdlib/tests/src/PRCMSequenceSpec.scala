// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

import utest.*

object PRCMSequenceSpec extends TestSuite:
  val tests = Tests:
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
      // Digest of the action vectors from an independent implementation of the same sequence.
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

    test("shared service targets complete under fair feedback"):
      val model   = new PRCMServiceModel
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

    test("receiver feedback progresses without internal fairness assumptions"):
      Seq(1, 8).foreach: stages =>
        val model    = new PRCMReceiverModel(stages)
        val progress = model.progress
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
