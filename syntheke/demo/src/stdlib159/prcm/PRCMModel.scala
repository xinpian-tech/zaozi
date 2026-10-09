package me.jiuyang.stdlib.prcm

// The behavior models of zaozi PR #159's PRCM sequences (PRCMDomainModel.scala, PRCMServiceModel.scala,
// PRCMReceiverModel.scala, PRCMCompositionProof.scala), checked before RTL. #159 states each safety rule as an SMT
// query over the enumerated reachable states; here the same rules are checked on that enumeration directly. The proofs
// that the RTL refines these models stay in #159.

/** One managed domain: its sequence phase, the target it follows and the feedback it observes. */
extension (control: PRCMControl)
  /** The feedback that settles once the domain follows these requests. */
  private[prcm] def settled: PRCMFeedback = PRCMFeedback(control.power, control.reset, control.isolation, control.quiesce)

/** The phase a completed target rests in. */
private[prcm] def settledPhase(target: PRCMTarget): PRCMPhase = target match
  case PRCMTarget.Off   => PRCMPhase.Off
  case PRCMTarget.Reset => PRCMPhase.Reset
  case PRCMTarget.Run   => PRCMPhase.Run

/** A consumer that no longer holds its provider: settled Off or Reset, or settled after a fault. */
private[prcm] def isReleased(phase: PRCMPhase, feedback: PRCMFeedback): Boolean =
  PRCMSequence.complete(phase, PRCMTarget.Off, feedback) || PRCMSequence.complete(phase, PRCMTarget.Reset, feedback) ||
    (phase == PRCMPhase.FaultOff && feedback.bits == 14)

private[prcm] final case class PRCMFrame(phase: PRCMPhase, target: PRCMTarget, feedback: PRCMFeedback):
  def advance(request: Option[PRCMTarget]): PRCMFrame =
    val selected = request.getOrElse(target)
    copy(phase = PRCMSequence.step(phase, selected, feedback, false), target = selected)

  /** Every way the feedback may follow the requests: each bit either holds or settles. */
  def responses: Vector[PRCMFrame] =
    val changed = feedback.bits ^ PRCMSequence.control(phase).settled.bits
    (0 until 16).toVector.filter(mask => (mask & ~changed) == 0).map { mask =>
      copy(feedback = PRCMFeedback.fromBits(feedback.bits ^ mask))
    }

  def complete(request: Option[PRCMTarget]): Boolean =
    request.contains(target) && PRCMSequence.complete(phase, target, feedback)

  /** Feedback bit `bit` agrees with what the phase requests. */
  def settled(bit: Int): Boolean = ((feedback.bits ^ PRCMSequence.control(phase).settled.bits) & (1 << bit)) == 0

private[prcm] object PRCMFrame:
  val off: PRCMFrame = PRCMFrame(PRCMPhase.Init, PRCMTarget.Off, PRCMFeedback(false, true, true, true))

/** Checks of the PRCM sequences. Each returns the failures it finds. */
object PRCMModel:
  private val targets  = PRCMTarget.values.toVector
  private val requests = None +: targets.map(Some(_))

  private def show[S](states: Vector[S]): String = states.mkString(" -> ")

  private def stalled[S](what: String, found: Option[(Vector[S], Vector[S])]): Option[String] =
    found.map((prefix, loop) => s"$what never completes: ${show(prefix)}, then forever ${show(loop)}")

  private def rules[S](what: String, states: Iterable[S])(broken: (String, S => Boolean)*): Vector[String] =
    broken.toVector.flatMap((name, broken) => states.find(broken).map(s => s"$what breaks $name at $s"))

  // ------------------------------------------------------------------ one domain

  /** A domain under every request, with feedback that holds or follows. Service admission can hold Reset even
    * without a software Reset mode, so every target is checked.
    */
  def domain: Vector[String] =
    def next(frame: PRCMFrame): Vector[PRCMFrame] = requests.flatMap(frame.advance(_).responses)
    val reachable = ModelChecker.reachable(PRCMFrame.off)(next)

    final case class Step(before: PRCMFrame, request: Option[PRCMTarget]):
      val after                             = before.advance(request)
      val (was, now)                        = (PRCMSequence.control(before.phase), PRCMSequence.control(after.phase))
      def done                              = after.complete(request)
      val seen                              = before.feedback
      override def toString                 = s"$before under ${request.getOrElse("an invalid request")}"
    val steps = for frame <- reachable; request <- requests yield Step(frame, request)

    val safety = rules("the domain sequence", steps)(
      "power_reverse_low"        -> (s => !s.was.power && s.now.power && s.seen.power),
      "power_reverse_high"       -> (s => s.was.power && !s.now.power && !s.seen.power),
      "isolation_reverse_low"    -> (s => !s.was.isolation && s.now.isolation && s.seen.isolation),
      "isolation_reverse_high"   -> (s => s.was.isolation && !s.now.isolation && !s.seen.isolation),
      "quiesce_reverse_low"      -> (s => !s.was.quiesce && s.now.quiesce && s.seen.idle),
      "quiesce_reverse_high"     -> (s => s.was.quiesce && !s.now.quiesce && !s.seen.idle),
      "power_off_reset"          -> (s => s.was.power && !s.now.power && !s.seen.reset),
      "clock_off_reset"          -> (s => s.was.clock && !s.now.clock && !s.seen.reset),
      "power_off_isolation"      -> (s => s.was.power && !s.now.power && !s.seen.isolation),
      "clock_off_isolation"      -> (s => s.was.clock && !s.now.clock && !s.seen.isolation),
      "power_off_clock"          -> (s => s.was.power && !s.now.power && s.was.clock),
      "reset_release_power"      -> (s => s.was.reset && !s.now.reset && !s.seen.power),
      "reset_release_clock"      -> (s => s.was.reset && !s.now.reset && !s.was.clock),
      "reset_release_isolation"  -> (s => s.was.reset && !s.now.reset && !s.seen.isolation),
      "isolation_release_power"  -> (s => s.was.isolation && !s.now.isolation && !s.seen.power),
      "isolation_release_clock"  -> (s => s.was.isolation && !s.now.isolation && !s.was.clock),
      "isolation_release_reset"  -> (s => s.was.isolation && !s.now.isolation && s.seen.reset),
      "isolation_before_quiesce" -> (s => !s.was.isolation && s.now.isolation && !s.was.quiesce),
      "isolation_before_idle"    -> (s => !s.was.isolation && s.now.isolation && !s.seen.idle),
      "admission_power"          -> (s => !s.now.quiesce && !s.seen.power),
      "admission_reset"          -> (s => !s.now.quiesce && s.seen.reset),
      "admission_isolation"      -> (s => !s.now.quiesce && s.seen.isolation),
      "normal_power_loss"        -> (s => PRCMSequence.powerLost(s.before.phase, s.seen.power)),
      "invalid_done"             -> (s => s.request.isEmpty && s.done),
      "invalid_target"           -> (s => s.request.isEmpty && s.after.target != s.before.target),
      "done_phase"               -> (s => s.done && s.after.phase != settledPhase(s.after.target)),
      "done_target"              -> (s => s.done && !s.request.contains(s.after.target)),
      "done_power"               -> (s => s.done && s.seen.power == (s.after.target == PRCMTarget.Off)),
      "done_reset"               -> (s => s.done && s.seen.reset == (s.after.target == PRCMTarget.Run)),
      "done_isolation"           -> (s => s.done && s.seen.isolation == (s.after.target == PRCMTarget.Run)),
      "done_idle"                -> (s => s.done && s.seen.idle == (s.after.target == PRCMTarget.Run))
    )

    // Normal progress: under a stable target and fair feedback, the domain completes it.
    val progress = targets.flatMap { target =>
      stalled(
        s"the domain sequence toward $target",
        ModelChecker.stall(
          reachable,
          next,
          _.advance(Some(target)).responses,
          _.complete(Some(target)),
          (0 until 4).map(bit => (frame: PRCMFrame) => frame.settled(bit))
        )
      )
    }

    // A completed target stays completed while it is requested.
    val stable = targets.flatMap { target =>
      reachable.find(f => f.complete(Some(target)) && f.advance(Some(target)).responses.exists(!_.complete(Some(target))))
        .map(f => s"the domain sequence leaves completed $target at $f")
    }
    safety ++ progress ++ stable

  // ------------------------------------------------------------------ one service

  private final case class ServiceFrame(
    provider: PRCMFrame,
    consumer: PRCMFrame,
    phase:    PRCMServicePhase,
    grant:    Boolean,
    other:    Boolean):
    def request:       Boolean = PRCMServiceSequence.request(phase)
    def providerReady: Boolean = PRCMSequence.complete(provider.phase, PRCMTarget.Run, provider.feedback)
    def released:      Boolean = isReleased(consumer.phase, consumer.feedback)

    def advance(providerTarget: PRCMTarget, consumerTarget: PRCMTarget, otherPermission: Boolean): ServiceFrame =
      val failed        = request &&
        (if phase == PRCMServicePhase.Hold then !grant || !providerReady else PRCMSequence.fault(provider.phase))
      val consumerFault = PRCMSequence.fault(consumer.phase)
      val need          = consumerTarget == PRCMTarget.Run
      val permission    = phase == PRCMServicePhase.Hold && grant && providerReady && !PRCMSequence.fault(provider.phase)
      val wanted        =
        if need && (!permission || !otherPermission) && !consumerFault then
          if PRCMSequence.control(consumer.phase).power then PRCMTarget.Reset else PRCMTarget.Off
        else consumerTarget
      val supply        = if request then PRCMTarget.Run else providerTarget
      copy(
        provider = provider.copy(phase = PRCMSequence.step(provider.phase, supply, provider.feedback, false), target = supply),
        consumer = consumer.copy(phase = PRCMSequence.step(consumer.phase, wanted, consumer.feedback, failed), target = wanted),
        phase = PRCMServiceSequence.step(phase, need && !consumerFault, grant, failed, released),
        grant = request && providerReady
      )

    def responses: Vector[ServiceFrame] =
      for supply <- provider.responses; client <- consumer.responses yield copy(provider = supply, consumer = client)

    def complete(providerTarget: PRCMTarget, consumerTarget: PRCMTarget): Boolean =
      val running = consumerTarget == PRCMTarget.Run
      provider.complete(Some(if running then PRCMTarget.Run else providerTarget)) &&
      consumer.complete(Some(consumerTarget)) &&
      (if running then phase == PRCMServicePhase.Hold && grant && other else phase == PRCMServicePhase.Idle && !grant)

  /** A provider and a consumer joined by one service. `other` stands for every other service the consumer needs. */
  def service: Vector[String] =
    val pairs = for provider <- targets; consumer <- targets yield provider -> consumer
    def next(frame: ServiceFrame): Vector[ServiceFrame] =
      pairs.flatMap((p, c) => Vector(false, true).map(frame.advance(p, c, _))).distinct.flatMap(_.responses).distinct
    val initial   = ServiceFrame(PRCMFrame.off, PRCMFrame.off, PRCMServicePhase.Idle, false, false)
    val reachable = ModelChecker.reachable(initial)(next)

    def unavailable(f: ServiceFrame): Boolean =
      f.provider.phase != PRCMPhase.Run || !f.provider.feedback.power || f.provider.feedback.reset ||
        f.provider.feedback.isolation || f.provider.feedback.idle
    val safety = rules("the service handshake", reachable)(
      "provider_fault"         -> (f => PRCMSequence.fault(f.provider.phase)),
      "consumer_fault"         -> (f => PRCMSequence.fault(f.consumer.phase)),
      "accept"                 -> (f => f.request && f.grant && PRCMSequence.control(f.provider.phase).quiesce),
      "grant_unavailable"      -> (f => f.grant && unavailable(f)),
      "consumer_reset_request" -> (f =>
        !PRCMSequence.control(f.consumer.phase).reset &&
          (unavailable(f) || !f.request || !f.grant || f.phase != PRCMServicePhase.Hold)),
      "consumer_reset"         -> (f =>
        !f.consumer.feedback.reset && (unavailable(f) || !f.request || !f.grant || f.phase != PRCMServicePhase.Hold))
    )

    // Progress, with the consumer's other services granted fairly once requested.
    val states   = reachable ++ reachable.map(_.copy(other = true))
    val progress = pairs.flatMap { (provider, consumer) =>
      val fairness = (0 until 8).map(bit =>
        (f: ServiceFrame) => if bit < 4 then f.provider.settled(bit) else f.consumer.settled(bit - 4)
      ) :+ ((f: ServiceFrame) => consumer != PRCMTarget.Run || f.other)
      stalled(
        s"the service with provider $provider and consumer $consumer",
        ModelChecker.stall(
          states,
          f => next(f.copy(other = false)).flatMap(n => Vector(n, n.copy(other = true))),
          f =>
            f.advance(provider, consumer, f.other).responses
              .flatMap(r => (if f.other then Vector(true) else Vector(false, true)).map(o => r.copy(other = o))),
          _.complete(provider, consumer),
          fairness
        )
      )
    }

    // The handshake keeps its request while it waits or holds, and returns only once released.
    val persistence =
      import PRCMServicePhase.*
      def request(phase: PRCMServicePhase) = PRCMServiceSequence.request(phase)
      for
        phase <- PRCMServicePhase.values.toVector
        input <- 0 until 16
        (need, grant, fault, released) = ((input & 1) != 0, (input & 2) != 0, (input & 4) != 0, (input & 8) != 0)
        after                          = PRCMServiceSequence.step(phase, need, grant, fault, released)
        if Seq(
          (phase == Idle && need) -> (after == Wait && request(after)),
          (phase == Wait && !fault && !grant) -> (request(phase) && request(after)),
          (phase == Wait && !fault && grant) -> (after == Hold && request(after)),
          (phase == Hold && need && !fault) -> (after == Hold && request(phase) && request(after)),
          (phase == Hold && (!need || fault) && released) -> (after == Return && !request(after)),
          (phase == Return && !grant) -> (after == Idle)
        ).exists((when, holds) => when && !holds)
      yield s"the service handshake steps $phase to $after on need=$need grant=$grant fault=$fault released=$released"
    safety ++ progress ++ persistence

  // ------------------------------------------------------------------ reset receiver

  private final case class ReceiverFrame(frame: PRCMFrame, receiver: Int, feedback: Boolean)

  /** A domain whose reset feedback passes a `stages`-deep synchronizer, under faults and repeated cold reset. */
  def receiver(stages: Int): Vector[String] =
    require(stages > 0)
    val requests = targets.map(Some(_)) :+ None
    val initial  = ReceiverFrame(PRCMFrame.off, 0, true)

    // Sample before the management edge; apply asynchronous assertion again after the phase changes.
    def successors(state: ReceiverFrame, request: Option[PRCMTarget], serviceFault: Boolean, arbitraryPower: Boolean)
      : Vector[ReceiverFrame] =
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
        .flatMap { frame =>
          val powers = if arbitraryPower then Vector(false, true) else Vector(frame.feedback.power)
          powers.map(power =>
            ReceiverFrame(frame.copy(feedback = frame.feedback.copy(power = power)), receiver, state.receiver != stages)
          )
        }
        .distinct

    def normal(state: ReceiverFrame): Vector[ReceiverFrame] =
      requests.flatMap(successors(state, _, false, false))
    val coldResets = (0 until 16).filter(i => (i & 2) != 0).map { bits =>
      ReceiverFrame(PRCMFrame(PRCMPhase.Init, PRCMTarget.Off, PRCMFeedback.fromBits(bits)), 0, true)
    }
    def faulty(state: ReceiverFrame): Vector[ReceiverFrame] =
      (requests.flatMap(r => Vector(false, true).flatMap(successors(state, r, _, true))) ++ coldResets).distinct

    val reachable      = ModelChecker.reachable(initial)(normal)
    val faultReachable = ModelChecker.reachable(initial)(faulty)
    def fair(bit: Int) = (s: ReceiverFrame) => s.frame.settled(bit)
    val fairness       = Vector(0, 2, 3).map(fair)
    val what           = s"the domain with a $stages-stage reset receiver"

    val progress = targets.flatMap { target =>
      stalled(
        s"$what toward $target",
        ModelChecker.stall(
          reachable,
          normal,
          successors(_, Some(target), false, false),
          _.frame.complete(Some(target)),
          fairness
        )
      )
    }
    def offCycle(label: String, serviceFault: Boolean)(complete: ReceiverFrame => Boolean) =
      stalled(
        s"$what $label",
        ModelChecker.stall(
          faultReachable,
          faulty,
          s => (if serviceFault then Vector(false, true) else Vector(false))
            .flatMap(successors(s, Some(PRCMTarget.Off), _, false)),
          complete,
          fairness
        )
      )
    val recovery = offCycle("recovering to Off after a fault", false)(_.frame.complete(Some(PRCMTarget.Off)))
    val release  = offCycle("releasing its services under a continuing service fault", true)(s =>
      isReleased(s.frame.phase, s.frame.feedback)
    )

    final case class FaultStep(state: ReceiverFrame, request: Option[PRCMTarget], fault: Boolean):
      val before            = state.frame
      val target            = request.getOrElse(before.target)
      val next              = PRCMSequence.step(before.phase, target, before.feedback, fault)
      val (was, now)        = (PRCMSequence.control(before.phase), PRCMSequence.control(next))
      val (failed, failing) = (PRCMSequence.fault(before.phase), PRCMSequence.fault(next))
      override def toString = s"$state under ${request.getOrElse("an invalid request")}${if fault then " and a service fault" else ""}"
    val faultSafety = rules(what, for s <- faultReachable; r <- requests; f <- Vector(false, true) yield FaultStep(s, r, f))(
      "fault_protection"    -> (s => s.failing && !(s.now.reset && !s.now.clock && s.now.isolation)),
      "fault_detection"     -> (s =>
        ((s.fault && !s.failed) || PRCMSequence.powerLost(s.before.phase, s.before.feedback.power)) && !s.failing),
      "power_release"       -> (s =>
        s.was.power && !s.now.power && !(s.before.feedback.reset && s.before.feedback.isolation && !s.was.clock)),
      "fault_power_release" -> (s => s.failed && s.was.power && !s.now.power && !s.before.feedback.idle),
      "fault_exit"          -> (s =>
        s.failed && !s.failing && !(s.target == PRCMTarget.Off && s.next == PRCMPhase.Off && !s.before.feedback.power &&
          s.before.feedback.reset && s.before.feedback.isolation)),
      "receiver_protection" -> (s => (!s.was.clock || !s.was.power) && s.state.receiver != 0)
    )

    // The receiver model refines the domain model and keeps the domain in reset while it is unclocked or unpowered.
    val refinement =
      for
        s <- reachable
        r <- requests
        t <- successors(s, r, false, false)
        control = PRCMSequence.control(t.frame.phase)
        if !s.frame.advance(r).responses.contains(t.frame) ||
          (s.frame.feedback.reset != t.frame.feedback.reset && t.frame.feedback.reset != control.reset) ||
          ((!control.clock || !control.power) && t.receiver != 0)
      yield s"$what does not refine the domain sequence from $s under $r to $t"

    progress ++ recovery ++ release ++ faultSafety ++ refinement.take(1)

  /** A `stages`-deep reset synchronizer: its stage bits agree with the released-stage count the models keep. */
  def prefix(stages: Int): Vector[String] =
    val broken =
      for
        count    <- 0 to stages
        oldReset <- Seq(false, true) if !oldReset || count == 0
        reset    <- Seq(false, true)
        clock    <- Seq(false, true)
        advance   = clock && !oldReset
        nextCount = if reset then 0 else if advance then (count + 1).min(stages) else count
        bits      = (0 until stages).map(i => !reset && (if advance then i == 0 || count > i - 1 else count > i))
        if bits.zipWithIndex.exists((bit, i) => bit != (nextCount > i))
      yield s"a $stages-stage reset synchronizer loses its stage count from $count released stages"
    broken.take(1).toVector
