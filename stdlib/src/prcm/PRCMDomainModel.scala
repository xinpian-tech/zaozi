// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

private[prcm] case class PRCMFrame(phase: PRCMPhase, target: PRCMTarget, feedback: PRCMFeedback):
  def advance(request: Option[PRCMTarget]): PRCMFrame =
    val selected = request.getOrElse(target)
    copy(phase = PRCMSequence.step(phase, selected, feedback, false), target = selected)

  def responses: Vector[PRCMFrame] =
    val control = PRCMSequence.control(phase)
    val desired = PRCMFeedback(control.power, control.reset, control.isolation, control.quiesce).bits
    val changed = feedback.bits ^ desired
    (0 until 16).iterator
      .filter(mask => (mask & ~changed) == 0)
      .map: mask =>
        copy(feedback = PRCMFeedback.fromBits((feedback.bits & ~mask) | (desired & mask)))
      .toVector

  def complete(request: Option[PRCMTarget]): Boolean =
    request.contains(target) && PRCMSequence.complete(phase, target, feedback)

private[prcm] class PRCMDomainModel(targets: Seq[PRCMTarget]):
  require(targets.contains(PRCMTarget.Off), "domain proof requires an Off recovery target")
  val requests: Vector[Option[PRCMTarget]] = None +: targets.distinct.sortBy(_.ordinal).map(Some(_)).toVector
  val initial = PRCMFrame(PRCMPhase.Init, PRCMTarget.Off, PRCMFeedback(false, true, true, true))
  val reachable: Vector[PRCMFrame] = PRCMGraph.reachable(initial): frame =>
    requests.flatMap(request => frame.advance(request).responses)

  def progress: Option[(PRCMTarget, Vector[PRCMFrame], Vector[PRCMFrame])] =
    val index      = reachable.zipWithIndex.toMap
    val all        = reachable.indices.toSet
    val reachEdges = reachable.map(frame => requests.flatMap(r => frame.advance(r).responses).map(index).distinct)
    targets.distinct
      .sortBy(_.ordinal)
      .iterator
      .flatMap: target =>
        val edges    = reachable.map(frame => frame.advance(Some(target)).responses.map(index))
        val active   = all.filter(i => !reachable(i).complete(Some(target)))
        val fairness = (0 until 4).map: bit =>
          all.filter: i =>
            val frame   = reachable(i)
            val control = PRCMSequence.control(frame.phase)
            val desired = PRCMFeedback(control.power, control.reset, control.isolation, control.quiesce)
            ((frame.feedback.bits ^ desired.bits) & (1 << bit)) == 0
        PRCMGraph
          .fairCycle(edges, active, fairness.toVector)
          .map: loop =>
            (target, PRCMGraph.path(reachEdges, 0, loop.head, all).map(reachable), loop.map(reachable))
      .nextOption()
