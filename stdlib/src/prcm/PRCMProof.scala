// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import me.jiuyang.smtlib.SMTQuery
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.{Z3Result, Z3Status}
import me.jiuyang.smtlib.tpe.{Bool as SMTBool, Referable as SMTValue}

case class PRCMProofCheck(
  name:     String,
  expected: Z3Status,
  result:   Z3Result,
  query:    SMTQuery,
  sources: Map[String, String] = Map.empty):
  def passed:   Boolean     = result.status == expected
  def conflict: Seq[String] = result.conflict.map(sources)

case class PRCMProgressFailure(targets: Map[String, PRCMTarget], prefix: Seq[ujson.Value], loop: Seq[ujson.Value])

case class PRCMModelProof(checks: Seq[PRCMProofCheck], progressFailure: Option[PRCMProgressFailure]):
  def passed: Boolean = checks.forall(_.passed) && progressFailure.isEmpty

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

  def writeReport(parameter: PRCMParameter, design: os.Path, directory: os.Path, timeoutMs: Int): Boolean =
    val modelPassed                 = writeModelReport(parameter, directory / "model", timeoutMs)
    val input                       = os.read(design)
    val cells                       = os.read(design / os.up / "ClockCells.sv")
    val (ir, metadata)              = PRCMStateCut.lower(input, PRCM.moduleName(parameter), cells)
    val (feasible, bindings, reset) = PRCMAssemblyBindings.queries(parameter, ir, metadata)
    val receivers                   = parameter.orderedDomains.map(_.resetStages).distinct.sorted.map(n => n -> new PRCMReceiverModel(n))
    val queries                     = Seq(
      ("assembly.feasible", Z3Status.Sat, feasible),
      ("assembly.bindings", Z3Status.Unsat, bindings),
      ("assembly.coldReset", Z3Status.Unsat, reset),
      ("cold.prefixInduction", Z3Status.Unsat, new PRCMReceiverModel(parameter.coldResetStages).prefixInduction)
    ) ++
      receivers.flatMap: (stages, model) =>
        Seq(
          (s"receiver_$stages.feasible", Z3Status.Sat, model.refinement(false)),
          (s"receiver_$stages.refinement", Z3Status.Unsat, model.refinement(true)),
          (s"receiver_$stages.prefixInduction", Z3Status.Unsat, model.prefixInduction),
          (s"receiver_$stages.faults.feasible", Z3Status.Sat, model.faultSafety(false)),
          (s"receiver_$stages.faults.safety", Z3Status.Unsat, model.faultSafety(true))
        )
    os.write(directory / "design.mlir", input)
    os.write(directory / "ClockCells.sv", cells)
    os.write(directory / "relation.mlir", ir)
    os.write(directory / "state.json", metadata.render(indent = 2))
    val composition                 = Option.when(parameter.serviceEdges.nonEmpty)(PRCMCompositionProof.queries).toSeq.flatten
    val checks                      = (queries ++ composition).map: (name, expected, query) =>
      val result = query.check(timeoutMs)
      os.write(directory / s"$name.smt2", query.replay)
      PRCMProofCheck(name, expected, result, query)
    val progress                    = receivers.flatMap: (stages, model) =>
      model.progress.map: (target, prefix, loop) =>
        ujson.Obj(
          "resetStages" -> stages,
          "target"      -> target.toString,
          "prefix"      -> ujson.Arr.from(prefix.map(encode)),
          "loop"        -> ujson.Arr.from(loop.map(encode))
        )
    val recovery                    = receivers.flatMap: (stages, model) =>
      model.faultRecovery.map: (prefix, loop) =>
        ujson.Obj(
          "resetStages" -> stages,
          "target"      -> "Off",
          "prefix"      -> ujson.Arr.from(prefix.map(encode)),
          "loop"        -> ujson.Arr.from(loop.map(encode))
        )
    val release                     = receivers.flatMap: (stages, model) =>
      model.faultRelease.map: (prefix, loop) =>
        ujson.Obj(
          "resetStages" -> stages,
          "target"      -> "Off",
          "prefix"      -> ujson.Arr.from(prefix.map(encode)),
          "loop"        -> ujson.Arr.from(loop.map(encode))
        )
    val passed                      = modelPassed && checks.forall(_.passed) && progress.isEmpty && recovery.isEmpty && release.isEmpty
    os.write(
      directory / "report.json",
      ujson
        .Obj(
          "scope"            -> "PRCM models, assembly state bindings, and receiver progress",
          "passed"           -> passed,
          "z3"               -> SMTQuery.solverVersion,
          "circt"            -> os.proc("circt-opt", "--version").call().out.text().trim,
          "verilogFrontend"  -> os.proc("circt-verilog", "--version").call().out.text().trim,
          "modelReport"      -> "model/report.json",
          "checks"           -> ujson.Arr.from(checks.map: check =>
            ujson.Obj(
              "name"     -> check.name,
              "expected" -> check.expected.toString,
              "result"   -> check.result.status.toString,
              "query"    -> s"${check.name}.smt2",
              "model"    -> ujson.Obj.from(check.result.model.map((name, value) => name -> ujson.Str(value.toString)))
            )),
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
            "CIRCT register and imported Verilog latch semantics; management clock has a low interval before each rising edge.",
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
    passed

  def writeDomainReport(
    parameter: PRCMDomainParameter,
    design:    os.Path,
    directory: os.Path,
    timeoutMs: Int
  ): Boolean =
    val input                       = os.read(design)
    val cells                       = os.read(design / os.up / "ClockCells.sv")
    val (ir, metadata)              = PRCMStateCut.lower(input, PRCMDomain.moduleName(parameter), cells)
    val (feasible, bindings, reset) = PRCMDomainBindings.queries(ir, metadata, parameter.resetStages)
    val receiver                    = new PRCMReceiverModel(parameter.resetStages)
    val queries                     = Seq(
      ("domain.feasible", Z3Status.Sat, feasible),
      ("domain.bindings", Z3Status.Unsat, bindings),
      ("domain.coldReset", Z3Status.Unsat, reset),
      ("receiver.feasible", Z3Status.Sat, receiver.refinement(false)),
      ("receiver.refinement", Z3Status.Unsat, receiver.refinement(true)),
      ("receiver.prefixInduction", Z3Status.Unsat, receiver.prefixInduction)
    )
    os.makeDir.all(directory)
    os.write(directory / "design.mlir", input)
    os.write(directory / "ClockCells.sv", cells)
    os.write(directory / "relation.mlir", ir)
    os.write(directory / "state.json", metadata.render(indent = 2))
    val checks                      = queries.map: (name, expected, query) =>
      val result = query.check(timeoutMs)
      os.write(directory / s"$name.smt2", query.replay)
      PRCMProofCheck(name, expected, result, query)
    val progress                    = receiver.progress.map: (target, prefix, loop) =>
      ujson.Obj(
        "target" -> target.toString,
        "prefix" -> ujson.Arr.from(prefix.map(encode)),
        "loop"   -> ujson.Arr.from(loop.map(encode))
      )
    val passed                      = checks.forall(_.passed) && progress.isEmpty
    val results                     = checks.map: check =>
      ujson.Obj(
        "name"     -> check.name,
        "expected" -> check.expected.toString,
        "result"   -> check.result.status.toString,
        "query"    -> s"${check.name}.smt2",
        "model"    -> ujson.Obj.from(check.result.model.map((name, value) => name -> ujson.Str(value.toString)))
      )
    os.write(
      directory / "report.json",
      ujson
        .Obj(
          "scope"           -> "domain state bindings and normal reset-feedback refinement",
          "passed"          -> passed,
          "resetStages"     -> parameter.resetStages,
          "z3"              -> SMTQuery.solverVersion,
          "circt"           -> os.proc("circt-opt", "--version").call().out.text().trim,
          "checks"          -> ujson.Arr.from(results),
          "progressFailure" -> progress.getOrElse(ujson.Null),
          "assumptions"     -> ujson.Arr(
            "CIRCT seq.firreg asynchronous reset and the imported Verilog clock-cell latch semantics.",
            "Target is one of Off=0, Reset=1 or Run=2.",
            "Management clock has a low interval before each rising edge and continues during progress.",
            "Normal execution begins after cold reset with power off, isolation asserted and idle acknowledged.",
            "External feedback holds or follows requests; progress requires a stable target and fair external feedback.",
            "Power and service faults and repeated cold reset are excluded from normal progress."
          )
        )
        .render(indent = 2)
    )
    passed

  def writeModelReport(parameter: PRCMParameter, directory: os.Path, timeoutMs: Int): Boolean =
    val configurations = configuration(parameter, timeoutMs)
    val circuits       = controls(timeoutMs)
    val models         = parameter.orderedDomains.map(domain(_, timeoutMs)) ++
      Option.when(parameter.serviceEdges.nonEmpty)(service(timeoutMs))
    val checks         = configurations ++ circuits ++ models.flatMap(_.checks)
    val failures       = models.flatMap(_.progressFailure)
    val passed         = checks.forall(_.passed) && failures.isEmpty
    os.makeDir.all(directory)
    os.write(directory / "config.json", upickle.default.write(parameter, indent = 2))
    val results        = checks.zipWithIndex.map: (check, index) =>
      val filename     = s"query_$index.smt2"
      os.write(directory / filename, check.query.replay)
      val model        = check.result.model.map: (name, value) =>
        name -> (value match
          case boolean: Boolean => ujson.Bool(boolean)
          case integer: BigInt  => ujson.Str(integer.toString))
      ujson.Obj(
        "name"     -> check.name,
        "expected" -> check.expected.toString,
        "result"   -> check.result.status.toString,
        "query"    -> filename,
        "model"    -> ujson.Obj.from(model),
        "conflict" -> ujson.Arr.from(check.conflict)
      )
    val progress       = failures.map: failure =>
      ujson.Obj(
        "targets" -> ujson.Obj.from(failure.targets.map((name, target) => name -> ujson.Str(target.toString))),
        "prefix"  -> ujson.Arr.from(failure.prefix),
        "loop"    -> ujson.Arr.from(failure.loop)
      )
    val report         = ujson.Obj(
      "scope"            -> "configuration, action models, and combinational control circuits",
      "passed"           -> passed,
      "z3"               -> SMTQuery.solverVersion,
      "circt"            -> os.proc("circt-opt", "--version").call().out.text().trim,
      "checks"           -> ujson.Arr.from(results),
      "progressFailures" -> ujson.Arr.from(progress),
      "assumptions"      -> ujson.Arr(
        "The normal model starts with power off, reset and isolation asserted, and idle acknowledged.",
        "Normal feedback holds its value or follows its request; power and service faults are excluded.",
        "Progress requires a stable target and fair feedback; other service permission eventually remains available."
      )
    )
    os.write(directory / "report.json", report.render(indent = 2))
    passed

  def configuration(parameter: PRCMParameter, timeoutMs: Int): Seq[PRCMProofCheck] =
    PRCMConfigurationProof
      .queries(parameter)
      .map: (name, query, sources) =>
        PRCMProofCheck(name, Z3Status.Sat, query.check(timeoutMs), query, sources)

  def controls(timeoutMs: Int): Seq[PRCMProofCheck] =
    Seq(
      PRCMSequenceDecoder.domainNext,
      PRCMSequenceDecoder.domainControl,
      PRCMSequenceDecoder.serviceNext,
      PRCMSequenceDecoder.serviceControl
    ).flatMap: parameter =>
      val (feasible, equivalent) = PRCMControlProof.queries(parameter)
      val existence              = PRCMProofCheck(s"${parameter.name}.feasible", Z3Status.Sat, feasible.check(timeoutMs), feasible)
      if !existence.passed then Seq(existence)
      else
        Seq(
          existence,
          PRCMProofCheck(s"${parameter.name}.equivalent", Z3Status.Unsat, equivalent.check(timeoutMs), equivalent)
        )

  def domain(domain: PRCMManagedDomain, timeoutMs: Int): PRCMModelProof =
    // Service admission can hold Reset even without a software Reset mode.
    val model     = new PRCMDomainModel(PRCMTarget.values.toSeq)
    val feasible  = domainQuery(model, false)
    val existence = PRCMProofCheck(s"${domain.name}.feasible", Z3Status.Sat, feasible.check(timeoutMs), feasible)
    if !existence.passed then PRCMModelProof(Seq(existence), None)
    else
      val query    = domainQuery(model, true)
      val safety   = PRCMProofCheck(s"${domain.name}.safety", Z3Status.Unsat, query.check(timeoutMs), query)
      val progress = model.progress.map: (target, prefix, loop) =>
        PRCMProgressFailure(Map(domain.name -> target), prefix.map(encode), loop.map(encode))
      PRCMModelProof(Seq(existence, safety), progress)

  def service(timeoutMs: Int): PRCMModelProof =
    val model     = new PRCMServiceModel
    val feasible  = serviceQuery(model, false)
    val existence = PRCMProofCheck("service.feasible", Z3Status.Sat, feasible.check(timeoutMs), feasible)
    if !existence.passed then PRCMModelProof(Seq(existence), None)
    else
      val query    = serviceQuery(model, true)
      val safety   = PRCMProofCheck("service.safety", Z3Status.Unsat, query.check(timeoutMs), query)
      val progress = model.progress.map: (targets, prefix, loop) =>
        PRCMProgressFailure(
          Map("provider" -> targets._1, "consumer" -> targets._2),
          prefix.map(encode),
          loop.map(encode)
        )
      PRCMModelProof(Seq(existence, safety), progress)

  private[prcm] def serviceQuery(model: PRCMServiceModel, checkViolation: Boolean): SMTQuery = SMTQuery.build:
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

  private[prcm] def domainQuery(model: PRCMDomainModel, checkViolation: Boolean): SMTQuery = SMTQuery.build:
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
