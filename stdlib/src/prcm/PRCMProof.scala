// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import java.lang.foreign.Arena

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.{Bool as SMTBool, Referable as SMTValue}
import me.jiuyang.zaozi.default.Elaborate
import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, Module, given}

case class PRCMProgressFailure(targets: Map[String, PRCMTarget], prefix: Seq[ujson.Value], loop: Seq[ujson.Value])

/** Queries for z3 and the result of the reachability check done in Scala. */
case class PRCMModelProof(queries: Seq[PRCMQuery], progressFailure: Option[PRCMProgressFailure])

object PRCMProof:
  private def encode(frame: PRCMFrame): ujson.Value = ujson.Obj(
    "phase"    -> frame.phase.toString,
    "target"   -> frame.target.toString,
    "feedback" -> ujson.Obj(
      "power"     -> frame.feedback.power,
      "reset"     -> frame.feedback.reset,
      "isolation" -> frame.feedback.isolation,
      "idle"      -> frame.feedback.idle
    )
  )

  private def encode(frame: PRCMServiceFrame): ujson.Value = ujson.Obj(
    "provider"        -> encode(frame.provider),
    "consumer"        -> encode(frame.consumer),
    "phase"           -> frame.phase.toString,
    "grant"           -> frame.grant,
    "otherPermission" -> frame.other
  )

  private def encode(frame: PRCMReceiverFrame): ujson.Value = ujson.Obj(
    "domain"            -> encode(frame.frame),
    "releasedStages"    -> frame.receiver,
    "resetFeedbackHead" -> frame.feedback
  )

  private def lowered[T](
    elaborate: (Arena, Context) ?=> Module,
    topName:   String
  )(body:      (Arena, Context) ?=> (Module, ujson.Value) => T
  ): T =
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      try
        PRCMStateCut.prepare
        val module = elaborate
        try body(module, PRCMStateCut.lower(module, topName))
        finally module.destroy()
      finally summon[Context].destroy()
    finally arena.close()

  private def offCycle(stages: Int, found: Option[(Vector[PRCMReceiverFrame], Vector[PRCMReceiverFrame])]) =
    found.map: (prefix, loop) =>
      ujson.Obj(
        "resetStages" -> stages,
        "target"      -> "Off",
        "prefix"      -> ujson.Arr.from(prefix.map(encode)),
        "loop"        -> ujson.Arr.from(loop.map(encode))
      )

  /** Writes every model and assembly query; returns whether the Scala reachability checks passed. */
  def writeReport(parameter: PRCMParameter, directory: os.Path): Boolean =
    val modelPassed                 = writeModelReport(parameter, directory / "model")
    val (feasible, bindings, reset) =
      lowered(Elaborate(PRCM, parameter), PRCM.moduleName(parameter)): (module, metadata) =>
        os.write.over(directory / "state.json", metadata.render(indent = 2))
        PRCMAssemblyBindings.queries(parameter, module, metadata)
    val receivers                   =
      parameter.orderedDomains.map(_.resetStages).distinct.sorted.map(n => n -> new PRCMReceiverModel(n))
    val queries                     = Seq(
      PRCMQuery.sat("assembly.feasible", feasible),
      PRCMQuery.unsat("assembly.bindings", bindings),
      PRCMQuery.unsat("assembly.coldReset", reset),
      PRCMQuery.unsat("cold.prefixInduction", new PRCMReceiverModel(parameter.coldResetStages).prefixInduction)
    ) ++
      receivers.flatMap: (stages, model) =>
        Seq(
          PRCMQuery.sat(s"receiver_$stages.feasible", model.refinement(false)),
          PRCMQuery.unsat(s"receiver_$stages.refinement", model.refinement(true)),
          PRCMQuery.unsat(s"receiver_$stages.prefixInduction", model.prefixInduction),
          PRCMQuery.sat(s"receiver_$stages.faults.feasible", model.faultSafety(false)),
          PRCMQuery.unsat(s"receiver_$stages.faults.safety", model.faultSafety(true))
        )
      ++ Option.when(parameter.serviceEdges.nonEmpty)(PRCMCompositionProof.queries).toSeq.flatten
    val progress                    = receivers.flatMap: (stages, model) =>
      model.progress.map: (target, prefix, loop) =>
        ujson.Obj(
          "resetStages" -> stages,
          "target"      -> target.toString,
          "prefix"      -> ujson.Arr.from(prefix.map(encode)),
          "loop"        -> ujson.Arr.from(loop.map(encode))
        )
    val recovery                    = receivers.flatMap((stages, model) => offCycle(stages, model.faultRecovery))
    val release                     = receivers.flatMap((stages, model) => offCycle(stages, model.faultRelease))
    os.write.over(
      directory / "report.json",
      ujson
        .Obj(
          "scope"            -> "PRCM models, assembly state bindings, and receiver progress",
          "modelReport"      -> "model/report.json",
          "queries"          -> PRCMQuery.write(directory, queries),
          "progressFailures" -> ujson.Arr.from(progress),
          "recoveryFailures" -> ujson.Arr.from(recovery),
          "releaseFailures"  -> ujson.Arr.from(release),
          "offRecovery"      -> ujson.Obj(
            "local"     -> parameter.chip.isEmpty,
            "chipModes" -> ujson.Arr.from(parameter.chipTargets.collect:
              case (mode, targets) if targets.forall(_.forall(_ == PRCMTarget.Off)) => mode.name)
          ),
          "composition"      -> ujson.Arr(
            "Consumers are not providers, so their stable targets request services independently of permission.",
            "Pending requests hold providers at Run; domain progress and terminal stability establish persistent grants.",
            "All required grants can arrive independently, discharging other service permission in the local progress check.",
            "Off consumers release despite continuing service fault, allowing requests to return before provider recovery."
          ),
          "assumptions"      -> ujson.Arr(
            "CIRCT register and clock-role latch semantics; management clock has a low interval before each rising edge.",
            "Retained targets are legal after reset; assembly bindings preserve that invariant.",
            "Normal execution starts after cold reset with power off, isolation asserted and idle acknowledged.",
            "Normal progress requires stable targets, continuing clock cycles, fair external feedback and no new faults/reset.",
            "Recovery requires Off, no further reset or fault input, continuing management clocks and fair external feedback.",
            "Consumer release requires Off and fair external feedback even while service fault continues.",
            "Off recovery uses effective Off targets; the selected chip policy must permit them."
          )
        )
        .render(indent = 2)
    )
    modelPassed && progress.isEmpty && recovery.isEmpty && release.isEmpty

  def writeDomainReport(parameter: PRCMDomainParameter, directory: os.Path): Boolean =
    os.makeDir.all(directory)
    val (feasible, bindings, reset) =
      lowered(Elaborate(PRCMDomain, parameter), PRCMDomain.moduleName(parameter)): (module, metadata) =>
        os.write.over(directory / "state.json", metadata.render(indent = 2))
        PRCMDomainBindings.queries(module, metadata, parameter.resetStages)
    val receiver                    = new PRCMReceiverModel(parameter.resetStages)
    val queries                     = Seq(
      PRCMQuery.sat("domain.feasible", feasible),
      PRCMQuery.unsat("domain.bindings", bindings),
      PRCMQuery.unsat("domain.coldReset", reset),
      PRCMQuery.sat("receiver.feasible", receiver.refinement(false)),
      PRCMQuery.unsat("receiver.refinement", receiver.refinement(true)),
      PRCMQuery.unsat("receiver.prefixInduction", receiver.prefixInduction)
    )
    val progress                    = receiver.progress.map: (target, prefix, loop) =>
      ujson.Obj(
        "target" -> target.toString,
        "prefix" -> ujson.Arr.from(prefix.map(encode)),
        "loop"   -> ujson.Arr.from(loop.map(encode))
      )
    os.write.over(
      directory / "report.json",
      ujson
        .Obj(
          "scope"           -> "domain state bindings and normal reset-feedback refinement",
          "resetStages"     -> parameter.resetStages,
          "queries"         -> PRCMQuery.write(directory, queries),
          "progressFailure" -> progress.getOrElse(ujson.Null),
          "assumptions"     -> ujson.Arr(
            "CIRCT seq.firreg asynchronous reset and the clock-role latch semantics.",
            "Target is one of Off=0, Reset=1 or Run=2.",
            "Management clock has a low interval before each rising edge and continues during progress.",
            "Normal execution begins after cold reset with power off, isolation asserted and idle acknowledged.",
            "External feedback holds or follows requests; progress requires a stable target and fair external feedback.",
            "Power and service faults and repeated cold reset are excluded from normal progress."
          )
        )
        .render(indent = 2)
    )
    progress.isEmpty

  def writeModelReport(parameter: PRCMParameter, directory: os.Path): Boolean =
    val models   = parameter.orderedDomains.map(domain) ++ Option.when(parameter.serviceEdges.nonEmpty)(service)
    val failures = models.flatMap(_.progressFailure)
    os.makeDir.all(directory)
    os.write.over(directory / "config.json", upickle.default.write(parameter, indent = 2))
    val report   = ujson.Obj(
      "scope"            -> "action models and combinational control circuits",
      "queries"          -> PRCMQuery.write(directory, controls ++ models.flatMap(_.queries)),
      "progressFailures" -> ujson.Arr.from(failures.map: failure =>
        ujson.Obj(
          "targets" -> ujson.Obj.from(failure.targets.map((name, target) => name -> ujson.Str(target.toString))),
          "prefix"  -> ujson.Arr.from(failure.prefix),
          "loop"    -> ujson.Arr.from(failure.loop)
        )),
      "assumptions"      -> ujson.Arr(
        "The normal model starts with power off, reset and isolation asserted, and idle acknowledged.",
        "Normal feedback holds its value or follows its request; power and service faults are excluded.",
        "Progress requires a stable target and fair feedback; other service permission eventually remains available."
      )
    )
    os.write.over(directory / "report.json", report.render(indent = 2))
    failures.isEmpty

  def controls: Seq[PRCMQuery] =
    Seq(
      PRCMSequenceDecoder.domainNext,
      PRCMSequenceDecoder.domainControl,
      PRCMSequenceDecoder.serviceNext,
      PRCMSequenceDecoder.serviceControl
    ).flatMap: parameter =>
      val (feasible, equivalent) = PRCMControlProof.queries(parameter)
      Seq(
        PRCMQuery.sat(s"${parameter.name}.feasible", feasible),
        PRCMQuery.unsat(s"${parameter.name}.equivalent", equivalent)
      )

  def domain(domain: PRCMManagedDomain): PRCMModelProof =
    // Service admission can hold Reset even without a software Reset mode.
    val model = new PRCMDomainModel(PRCMTarget.values.toSeq)
    PRCMModelProof(
      Seq(
        PRCMQuery.sat(s"${domain.name}.feasible", domainQuery(model, false)),
        PRCMQuery.unsat(s"${domain.name}.safety", domainQuery(model, true))
      ),
      model.progress.map: (target, prefix, loop) =>
        PRCMProgressFailure(Map(domain.name -> target), prefix.map(encode), loop.map(encode))
    )

  def service: PRCMModelProof =
    val model = new PRCMServiceModel
    PRCMModelProof(
      Seq(
        PRCMQuery.sat("service.feasible", serviceQuery(model, false)),
        PRCMQuery.unsat("service.safety", serviceQuery(model, true))
      ),
      model.progress.map: (targets, prefix, loop) =>
        PRCMProgressFailure(
          Map("provider" -> targets._1, "consumer" -> targets._2),
          prefix.map(encode),
          loop.map(encode)
        )
    )

  private[prcm] def serviceQuery(model: PRCMServiceModel, checkViolation: Boolean): String = PRCMQuery.build:
    val names     = Vector(
      "request",
      "grant",
      "hold",
      "provider_run",
      "provider_power",
      "provider_reset",
      "provider_isolation",
      "provider_idle",
      "provider_quiesce",
      "provider_fault",
      "consumer_fault",
      "consumer_reset_request",
      "consumer_reset"
    )
    val variables = names.map(name => name -> smtValue(Bool, name)).toMap
    val state     = smtValue(SInt, "state")
    smtAssert((state >= 0.S) & (state < model.reachable.size.S))
    def assignment(values: Seq[(String, Boolean)]): SMTValue[SMTBool] =
      values.map((name, value) => if value then variables(name) else !variables(name)).reduce(_ & _)
    model.reachable.zipWithIndex.foreach: (frame, index) =>
      val feedback = frame.provider.feedback
      val values   = Vector(
        frame.request,
        frame.grant,
        frame.phase == PRCMServicePhase.Hold,
        frame.provider.phase == PRCMPhase.Run,
        feedback.power,
        feedback.reset,
        feedback.isolation,
        feedback.idle,
        PRCMSequence.control(frame.provider.phase).quiesce,
        PRCMSequence.fault(frame.provider.phase),
        PRCMSequence.fault(frame.consumer.phase),
        PRCMSequence.control(frame.consumer.phase).reset,
        frame.consumer.feedback.reset
      )
      smtAssert((state === index.S) ==> assignment(names.zip(values)))
    val unavailable = Vector(
      "provider_run"       -> false,
      "provider_power"     -> false,
      "provider_reset"     -> true,
      "provider_isolation" -> true,
      "provider_idle"      -> true
    )
    val failures = Vector(
      "provider_fault" -> Seq("provider_fault" -> true),
      "consumer_fault" -> Seq("consumer_fault" -> true),
      "accept"         -> Seq("request" -> true, "grant" -> true, "provider_quiesce" -> true)
    ) ++
      unavailable.map((name, value) => s"grant_$name" -> Seq("grant" -> true, name -> value)) ++
      Vector("consumer_reset_request", "consumer_reset").flatMap: active =>
        (unavailable ++ Vector("request" -> false, "grant" -> false, "hold" -> false)).map: (name, value) =>
          s"${active}_$name" -> Seq(active -> false, name -> value)
    val violations = failures.map: (name, conditions) =>
      val flag = smtValue(Bool, s"violation_$name")
      smtAssert(smtEq(flag, assignment(conditions)))
      flag
    if checkViolation then smtAssert(violations.reduce(_ | _))

  private[prcm] def domainQuery(model: PRCMDomainModel, checkViolation: Boolean): String = PRCMQuery.build:
    val controls  = Vector("power", "clock", "reset", "isolation", "quiesce")
    val feedback  = Vector("power", "reset", "isolation", "idle")
    val targets   = Vector("off", "reset", "run")
    val names     = controls.flatMap(name => Vector(s"before_$name", s"after_$name")) ++
      feedback.map("feedback_" + _) ++ targets.flatMap(name =>
        Vector(s"target_$name", s"next_target_$name", s"stable_$name", s"request_$name")
      ) ++
      Vector("request_invalid", "done", "power_lost")
    val variables = names.map(name => name -> smtValue(Bool, name)).toMap
    val state     = smtValue(SInt, "state")
    val request   = smtValue(SInt, "request")
    smtAssert((state >= 0.S) & (state < model.reachable.size.S))
    smtAssert((request >= 0.S) & (request < model.requests.size.S))

    def assignment(values: Seq[(String, Boolean)]): SMTValue[SMTBool]      =
      values.map((name, value) => if value then variables(name) else !variables(name)).reduce(_ & _)
    def control(prefix: String, phase: PRCMPhase):  Seq[(String, Boolean)] =
      val value = PRCMSequence.control(phase)
      controls
        .zip(Seq(value.power, value.clock, value.reset, value.isolation, value.quiesce))
        .map((name, bit) => s"${prefix}_$name" -> bit)
    def target(prefix: String, value: PRCMTarget):  Seq[(String, Boolean)] =
      targets.zipWithIndex.map((name, index) => s"${prefix}_$name" -> (value.ordinal == index))

    model.requests.zipWithIndex.foreach:  (selected, index) =>
      val values = targets.zipWithIndex.map((name, code) => s"request_$name" -> selected.exists(_.ordinal == code))
      smtAssert((request === index.S) ==> assignment(values :+ ("request_invalid" -> selected.isEmpty)))
    model.reachable.zipWithIndex.foreach: (frame, index) =>
      val value    = frame.feedback
      val observed = feedback
        .zip(Seq(value.power, value.reset, value.isolation, value.idle))
        .map((name, bit) => s"feedback_$name" -> bit)
      smtAssert(
        (state === index.S) ==> assignment(control("before", frame.phase) ++ target("target", frame.target) ++ observed)
      )
      model.requests.zipWithIndex.foreach: (selected, choice) =>
        val next   = frame.advance(selected)
        val values = control("after", next.phase) ++ target("next_target", next.target) ++ Seq(
          "power_lost"   -> PRCMSequence.powerLost(frame.phase, frame.feedback.power),
          "done"         -> next.complete(selected),
          "stable_off"   -> (next.phase == PRCMPhase.Off),
          "stable_reset" -> (next.phase == PRCMPhase.Reset),
          "stable_run"   -> (next.phase == PRCMPhase.Run)
        )
        smtAssert(((state === index.S) & (request === choice.S)) ==> assignment(values))

    def yes(name: String): (String, Boolean) = name -> true
    def no(name:  String): (String, Boolean) = name -> false
    val reversal = Vector("power" -> "power", "isolation" -> "isolation", "quiesce" -> "idle").flatMap:
      (output, observed) =>
        Vector(
          s"${output}_reverse_low"  -> Seq(no(s"before_$output"), yes(s"after_$output"), yes(s"feedback_$observed")),
          s"${output}_reverse_high" -> Seq(yes(s"before_$output"), no(s"after_$output"), no(s"feedback_$observed"))
        )
    val powerOff = Seq(yes("before_power"), no("after_power"))
    val clockOff = Seq(yes("before_clock"), no("after_clock"))
    val resetOff = Seq(yes("before_reset"), no("after_reset"))
    val isolationOff = Seq(yes("before_isolation"), no("after_isolation"))
    val protectedShutdown = Vector("reset", "isolation").flatMap: field =>
      Vector(
        s"power_off_$field" -> (powerOff :+ no(s"feedback_$field")),
        s"clock_off_$field" -> (clockOff :+ no(s"feedback_$field"))
      )
    val ordering = Vector(
      "power_off_clock"          -> (powerOff :+ yes("before_clock")),
      "reset_release_power"      -> (resetOff :+ no("feedback_power")),
      "reset_release_clock"      -> (resetOff :+ no("before_clock")),
      "reset_release_isolation"  -> (resetOff :+ no("feedback_isolation")),
      "isolation_release_power"  -> (isolationOff :+ no("feedback_power")),
      "isolation_release_clock"  -> (isolationOff :+ no("before_clock")),
      "isolation_release_reset"  -> (isolationOff :+ yes("feedback_reset")),
      "isolation_before_quiesce" -> Seq(no("before_isolation"), yes("after_isolation"), no("before_quiesce")),
      "isolation_before_idle"    -> Seq(no("before_isolation"), yes("after_isolation"), no("feedback_idle")),
      "admission_power"          -> Seq(no("after_quiesce"), no("feedback_power")),
      "admission_reset"          -> Seq(no("after_quiesce"), yes("feedback_reset")),
      "admission_isolation"      -> Seq(no("after_quiesce"), yes("feedback_isolation")),
      "normal_power_loss"        -> Seq(yes("power_lost")),
      "invalid_done"             -> Seq(yes("request_invalid"), yes("done"))
    )
    val completion = targets.flatMap: name =>
      val done     = Seq(yes("done"), yes(s"request_$name"))
      Vector(
        s"invalid_target_$name" -> Seq(yes("request_invalid"), yes(s"target_$name"), no(s"next_target_$name")),
        s"done_phase_$name"     -> (done :+ no(s"stable_$name")),
        s"done_target_$name"    -> (done :+ no(s"next_target_$name")),
        s"done_power_$name"     -> (done :+ (s"feedback_power"     -> (name == "off"))),
        s"done_reset_$name"     -> (done :+ (s"feedback_reset"     -> (name == "run"))),
        s"done_isolation_$name" -> (done :+ (s"feedback_isolation" -> (name == "run"))),
        s"done_idle_$name"      -> (done :+ (s"feedback_idle"      -> (name == "run")))
      )
    val violations = (reversal ++ protectedShutdown ++ ordering ++ completion).map: (name, conditions) =>
      val flag = smtValue(Bool, s"violation_$name")
      smtAssert(smtEq(flag, assignment(conditions)))
      flag
    if checkViolation then smtAssert(violations.reduce(_ | _))
