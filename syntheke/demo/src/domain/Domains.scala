package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import upickle.default.Writer



final case class ClockValue(hz: Int) derives Writer:
  require(hz > 0, s"clock frequency $hz Hz must be positive")

final case class ClockRequirement(minHz: Option[Int] = None, maxHz: Option[Int] = None) derives Writer:
  minHz.foreach(v => require(v > 0, s"minimum clock frequency $v Hz must be positive"))
  maxHz.foreach(v => require(v > 0, s"maximum clock frequency $v Hz must be positive"))
  require(
    minHz.zip(maxHz).forall((lo, hi) => lo <= hi),
    s"clock frequency range ${minHz.getOrElse("unbounded")}..${maxHz.getOrElse("unbounded")} Hz is empty"
  )

enum ClockWitness derives Writer:
  case RequirementMismatch(actualHz: Int, requiredMinHz: Option[Int], requiredMaxHz: Option[Int])

object ClockDomain extends Domain:
  type Value       = ClockValue
  type Requirement = ClockRequirement
  type Witness     = ClockWitness

  val key: DomainKey = DomainKey("me.jiuyang.syntheke.demo", "clock")

  val attachmentPolicy: AttachmentPolicy = AttachmentPolicy.allow(
    Set(
      AttachmentMethod.Direct,
      AttachmentMethod.Follow,
      AttachmentMethod.CarrierOut,
      AttachmentMethod.CarrierIn
    )
  )(_ => Right(()))

  def validate(
    value:        ClockValue,
    requirements: Vector[DomainRequirement[ClockRequirement]]
  ): Either[DomainViolation[ClockWitness], Unit] =
    val requiredMin = requirements.flatMap(_.value.minHz).maxOption
    val requiredMax = requirements.flatMap(_.value.maxHz).minOption
    val violated    = requirements.filter { requirement =>
      requirement.value.minHz.exists(value.hz < _) || requirement.value.maxHz.exists(value.hz > _)
    }
    if violated.isEmpty then Right(())
    else
      Left(
        DomainViolation(
          s"clock ${value.hz} Hz is outside the required interval " +
            s"${requiredMin.fold("unbounded")(_.toString)}..${requiredMax.fold("unbounded")(_.toString)} Hz",
          violated.map(_.origin).sortBy(_.toString),
          ClockWitness.RequirementMismatch(value.hz, requiredMin, requiredMax)
        )
      )

  val valueWriter:       Writer[ClockValue]       = summon
  val requirementWriter: Writer[ClockRequirement] = summon
  val witnessWriter:     Writer[ClockWitness]     = summon


enum ResetAssertion derives Writer:
  case Synchronous, Asynchronous

enum ResetRelease derives Writer:
  case Synchronous, Asynchronous

final case class ResetValue(
  activeHigh: Boolean,
  assertion:  ResetAssertion,
  release:    ResetRelease)
    derives Writer

final case class ResetRequirement(
  requireAsynchronousAssertion: Boolean = false,
  requireSynchronousRelease:    Boolean = false,
  requiredActiveHigh:           Option[Boolean] = None)
    derives Writer

enum ResetWitness derives Writer:
  case RequirementMismatch(value: ResetValue, requirements: Vector[ResetRequirement])

object ResetDomain extends Domain:
  type Value       = ResetValue
  type Requirement = ResetRequirement
  type Witness     = ResetWitness

  val key: DomainKey = DomainKey("me.jiuyang.syntheke.demo", "reset")

  val attachmentPolicy: AttachmentPolicy = AttachmentPolicy.allow(
    Set(
      AttachmentMethod.Direct,
      AttachmentMethod.Follow,
      AttachmentMethod.CarrierOut,
      AttachmentMethod.CarrierIn
    )
  )(_ => Right(()))

  private def accepts(value: ResetValue, requirement: ResetRequirement): Boolean =
    (!requirement.requireAsynchronousAssertion || value.assertion == ResetAssertion.Asynchronous) &&
      (!requirement.requireSynchronousRelease || value.release == ResetRelease.Synchronous) &&
      requirement.requiredActiveHigh.forall(_ == value.activeHigh)

  def validate(
    value:        ResetValue,
    requirements: Vector[DomainRequirement[ResetRequirement]]
  ): Either[DomainViolation[ResetWitness], Unit] =
    val violated = requirements.filterNot(r => accepts(value, r.value))
    if violated.isEmpty then Right(())
    else
      Left(
        DomainViolation(
          s"reset behavior $value does not satisfy ${violated.size} requirement(s)",
          violated.map(_.origin).sortBy(_.toString),
          ResetWitness.RequirementMismatch(value, violated.map(_.value).sortBy(_.toString))
        )
      )

  def compatible(domains: EdgeDomains): Constraint =
    val outward = domains.outward(ResetDomain)
    val inward  = domains.inward(ResetDomain)
    Seq(outward, inward).check { view =>
      val out = view.value(outward)
      val in = view.value(inward)
      if out.activeHigh == in.activeHigh &&
        out.release == ResetRelease.Synchronous && in.release == ResetRelease.Synchronous then Right(())
      else Left(Violation(s"reset endpoints are incompatible: outward $out, inward $in"))
    }

  val valueWriter:       Writer[ResetValue]       = summon
  val requirementWriter: Writer[ResetRequirement] = summon
  val witnessWriter:     Writer[ResetWitness]     = summon


sealed trait PowerValue derives Writer

object PowerValue:
  final case class Supply(millivolts: Int, alwaysOn: Boolean) extends PowerValue derives Writer:
    require(millivolts > 0, s"power voltage $millivolts mV must be positive")

  case object ExternalDigitalModel extends PowerValue

final case class PowerRequirement(
  minMillivolts:    Option[Int] = None,
  maxMillivolts:    Option[Int] = None,
  requiresAlwaysOn: Boolean = false)
    derives Writer:
  minMillivolts.foreach(v => require(v > 0, s"minimum power voltage $v mV must be positive"))
  maxMillivolts.foreach(v => require(v > 0, s"maximum power voltage $v mV must be positive"))
  require(
    minMillivolts.zip(maxMillivolts).forall((lo, hi) => lo <= hi),
    s"power voltage range ${minMillivolts.getOrElse("unbounded")}..${maxMillivolts.getOrElse("unbounded")} mV is empty"
  )

enum PowerWitness derives Writer:
  case RequirementMismatch(
    value:            PowerValue,
    requiredMinMv:    Option[Int],
    requiredMaxMv:    Option[Int],
    requiredAlwaysOn: Boolean)

object PowerDomain extends Domain:
  type Value       = PowerValue
  type Requirement = PowerRequirement
  type Witness     = PowerWitness

  val key: DomainKey = DomainKey("me.jiuyang.syntheke.demo", "power")

  val attachmentPolicy: AttachmentPolicy = AttachmentPolicy.allow(
    Set(AttachmentMethod.Direct, AttachmentMethod.Follow, AttachmentMethod.Contextual,
      AttachmentMethod.CarrierOut, AttachmentMethod.CarrierIn)
  )(_ => Right(()))

  private def accepts(value: PowerValue, requirement: PowerRequirement): Boolean = value match
    case PowerValue.Supply(millivolts, alwaysOn) =>
      requirement.minMillivolts.forall(millivolts >= _) &&
      requirement.maxMillivolts.forall(millivolts <= _) &&
      (!requirement.requiresAlwaysOn || alwaysOn)
    case PowerValue.ExternalDigitalModel         =>
      requirement.minMillivolts.isEmpty && requirement.maxMillivolts.isEmpty && !requirement.requiresAlwaysOn

  def validate(
    value:        PowerValue,
    requirements: Vector[DomainRequirement[PowerRequirement]]
  ): Either[DomainViolation[PowerWitness], Unit] =
    val violated       = requirements.filterNot(r => accepts(value, r.value))
    val requiredMin    = requirements.flatMap(_.value.minMillivolts).maxOption
    val requiredMax    = requirements.flatMap(_.value.maxMillivolts).minOption
    val requiredAlways = requirements.exists(_.value.requiresAlwaysOn)
    if violated.isEmpty then Right(())
    else
      Left(
        DomainViolation(
          s"power value $value is outside the required operating envelope",
          violated.map(_.origin).sortBy(_.toString),
          PowerWitness.RequirementMismatch(value, requiredMin, requiredMax, requiredAlways)
        )
      )

  def compatible(domains: EdgeDomains, allowModel: Boolean): Constraint =
    val outward = domains.outward(PowerDomain)
    val inward  = domains.inward(PowerDomain)
    Seq(outward, inward).check { view =>
      val out = view.value(outward)
      val in  = view.value(inward)
      val accepted = (out, in) match
        case (_: PowerValue.Supply, _: PowerValue.Supply) =>
          view.sameIdentity(outward, inward)
        case (PowerValue.ExternalDigitalModel, PowerValue.Supply(_, alwaysOn)) =>
          allowModel && alwaysOn
        case (PowerValue.Supply(_, alwaysOn), PowerValue.ExternalDigitalModel) =>
          allowModel && alwaysOn
        case (PowerValue.ExternalDigitalModel, PowerValue.ExternalDigitalModel) => allowModel
      if accepted then Right(())
      else Left(Violation(
        s"power crossing from ${outward.key.node.show} ($out) to ${inward.key.node.show} ($in) " +
          "requires an explicit power boundary with isolation and, for unequal voltages, level shifting"
      ))
    }

  val valueWriter:       Writer[PowerValue]       = summon
  val requirementWriter: Writer[PowerRequirement] = summon
  val witnessWriter:     Writer[PowerWitness]     = summon
