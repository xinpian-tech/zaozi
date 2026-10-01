// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

private[prcm] case class PRCMServiceFrame(
  provider: PRCMFrame,
  consumer: PRCMFrame,
  phase:    PRCMServicePhase,
  grant:    Boolean,
  other: Boolean):
  def request:       Boolean = PRCMServiceSequence.request(phase)
  def providerReady: Boolean = PRCMSequence.complete(provider.phase, PRCMTarget.Run, provider.feedback)
  def released:      Boolean =
    PRCMSequence.complete(consumer.phase, PRCMTarget.Off, consumer.feedback) ||
      PRCMSequence.complete(consumer.phase, PRCMTarget.Reset, consumer.feedback) ||
      (consumer.phase == PRCMPhase.FaultOff && consumer.feedback.bits == 14)

  def advance(providerTarget: PRCMTarget, consumerTarget: PRCMTarget, otherPermission: Boolean): PRCMServiceFrame =
    val failed        = request && (if phase == PRCMServicePhase.Hold then !grant || !providerReady
                             else PRCMSequence.fault(provider.phase))
    val consumerFault = PRCMSequence.fault(consumer.phase)
    val need          = consumerTarget == PRCMTarget.Run
    val permission    = phase == PRCMServicePhase.Hold && grant && providerReady && !PRCMSequence.fault(provider.phase)
    val wanted        =
      if need && (!permission || !otherPermission) && !consumerFault then
        if PRCMSequence.control(consumer.phase).power then PRCMTarget.Reset else PRCMTarget.Off
      else consumerTarget
    val supply        = if request then PRCMTarget.Run else providerTarget
    copy(
      provider =
        provider.copy(phase = PRCMSequence.step(provider.phase, supply, provider.feedback, false), target = supply),
      consumer =
        consumer.copy(phase = PRCMSequence.step(consumer.phase, wanted, consumer.feedback, failed), target = wanted),
      phase = PRCMServiceSequence.step(phase, need && !consumerFault, grant, failed, released),
      grant = request && providerReady
    )

  def responses: Vector[PRCMServiceFrame] =
    for
      supply <- provider.responses
      client <- consumer.responses
    yield copy(provider = supply, consumer = client)

  def complete(providerTarget: PRCMTarget, consumerTarget: PRCMTarget): Boolean =
    val running = consumerTarget == PRCMTarget.Run
    provider.complete(Some(if running then PRCMTarget.Run else providerTarget)) && consumer.complete(
      Some(consumerTarget)
    ) &&
    (if running then phase == PRCMServicePhase.Hold && grant && other
     else phase == PRCMServicePhase.Idle && !grant)

private[prcm] class PRCMServiceModel:
  val targets: Vector[(PRCMTarget, PRCMTarget)] = (for
    provider <- PRCMTarget.values
    consumer <- PRCMTarget.values
  yield provider -> consumer).toVector
  private val off = PRCMFrame(PRCMPhase.Init, PRCMTarget.Off, PRCMFeedback(false, true, true, true))
  val initial = PRCMServiceFrame(off, off, PRCMServicePhase.Idle, false, false)

  def successors(frame: PRCMServiceFrame): Vector[PRCMServiceFrame] =
    targets
      .flatMap: (provider, consumer) =>
        Vector(false, true).map(frame.advance(provider, consumer, _))
      .distinct
      .flatMap(_.responses)
      .distinct

  val reachable: Vector[PRCMServiceFrame] = PRCMGraph.reachable(initial)(successors)

  def progress: Option[((PRCMTarget, PRCMTarget), Vector[PRCMServiceFrame], Vector[PRCMServiceFrame])] =
    val nodes = reachable ++ reachable.map(_.copy(other = true))
    val index = nodes.zipWithIndex.toMap
    val all   = nodes.indices.toSet
    targets.iterator
      .flatMap: (provider, consumer) =>
        val edges            = nodes.map: frame =>
          val next = frame.advance(provider, consumer, frame.other)
          next.responses.flatMap: response =>
            (if frame.other then Vector(true) else Vector(false, true))
              .map(other => index(response.copy(other = other)))
        val active           = all.filter(i => !nodes(i).complete(provider, consumer))
        val responseFairness = (0 until 8).map: bit =>
          all.filter: i =>
            val frame   = if bit < 4 then nodes(i).provider else nodes(i).consumer
            val control = PRCMSequence.control(frame.phase)
            val desired = PRCMFeedback(control.power, control.reset, control.isolation, control.quiesce)
            ((frame.feedback.bits ^ desired.bits) & (1 << (bit % 4))) == 0
        val otherFairness    = all.filter(i => consumer != PRCMTarget.Run || nodes(i).other)
        PRCMGraph
          .fairCycle(edges, active, responseFairness.toVector :+ otherFairness)
          .map: loop =>
            val reachEdges = nodes.map(frame =>
              successors(frame.copy(other = false))
                .flatMap(next => Vector(next, next.copy(other = true)))
                .map(index)
            )
            val prefix     = PRCMGraph.path(reachEdges, 0, loop.head, all).map(nodes)
            ((provider, consumer), prefix, loop.map(nodes))
      .nextOption()
