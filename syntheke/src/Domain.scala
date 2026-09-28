package me.jiuyang.syntheke

import upickle.default.Writer

final case class DomainKey(namespace: String, name: String):
  require(namespace.nonEmpty, "domain namespace must be non-empty")
  require(name.nonEmpty, "domain name must be non-empty")
  def show: String = s"$namespace:$name"

enum AttachmentMethod derives CanEqual:
  case Direct, Follow, Contextual, CarrierOut, CarrierIn

sealed trait AttachmentProvenance:
  def method: AttachmentMethod

object AttachmentProvenance:
  final case class Direct(declaration: DomainDeclId) extends AttachmentProvenance:
    val method = AttachmentMethod.Direct

  final case class Contextual(declaration: DomainDeclId, providedAt: ModuleId) extends AttachmentProvenance:
    val method = AttachmentMethod.Contextual

  final case class Follow(targets: Vector[ModuleNodeId], anchor: AttachmentProvenance) extends AttachmentProvenance:
    val method = AttachmentMethod.Follow

  final case class CarrierOut(declaration: DomainDeclId, followed: Option[Follow]) extends AttachmentProvenance:
    val method = AttachmentMethod.CarrierOut

  final case class CarrierIn(
    bind: BindId,
    source: ModuleNodeId,
    declaration: DomainDeclId,
    anchor: AttachmentProvenance)
      extends AttachmentProvenance:
    val method = AttachmentMethod.CarrierIn

trait AttachmentPolicy:
  def permits(method:      AttachmentMethod):     Boolean
  def validate(provenance: AttachmentProvenance): Either[Violation, Unit]

object AttachmentPolicy:
  def allow(
    methods: Set[AttachmentMethod]
  )(check:   AttachmentProvenance => Either[Violation, Unit]
  ): AttachmentPolicy =
    new AttachmentPolicy:
      def permits(method: AttachmentMethod):          Boolean                 = methods(method)
      def validate(provenance: AttachmentProvenance): Either[Violation, Unit] =
        if permits(provenance.method) then check(provenance)
        else Left(Violation(s"attachment method ${provenance.method} is not permitted"))

final class DomainRequirementOrigin private[syntheke] (private[syntheke] val ordinal: Int):
  override def toString: String = s"requirement[$ordinal]"

final case class DomainRequirement[R](origin: DomainRequirementOrigin, value: R)

final case class DomainViolation[W](
  message: String,
  sources: Vector[DomainRequirementOrigin],
  witness: W)

trait Domain:
  type Value
  type Requirement
  type Witness

  def key:              DomainKey
  def attachmentPolicy: AttachmentPolicy

  def validate(
    value:        Value,
    requirements: Vector[DomainRequirement[Requirement]]
  ): Either[DomainViolation[Witness], Unit]

  def valueWriter:       Writer[Value]
  def requirementWriter: Writer[Requirement]
  def witnessWriter:     Writer[Witness]

private[syntheke] final class DesignOwner

trait ReadToken:
  type Value
  private[syntheke] def tokenOwner: DesignOwner

final class ReadPlan private[syntheke] (private[syntheke] val tokens: Vector[ReadToken])

object ReadPlan:
  def apply(tokens: ReadToken*): ReadPlan =
    new ReadPlan(tokens.toVector.distinct)

final class ReadValues private[syntheke] (values: Map[ReadToken, Any]):
  def apply[T <: ReadToken](token: T): token.Value =
    values
      .getOrElse(token, throw new IllegalArgumentException("token is not present in this sealed read plan"))
      .asInstanceOf[token.Value]

sealed trait DomainReadable[D <: Domain]:
  val domain: D
  type Value = domain.Value
  private[syntheke] def owner:       DesignOwner
  private[syntheke] def module:      ModuleId

final class DomainHandle[D <: Domain] private[syntheke] (
  val domain:                  D,
  val id:                      DomainDeclId,
  private[syntheke] val owner: DesignOwner,
  private[syntheke] val reads: Vector[DomainReadable[?]],
  private[syntheke] val order: Int,
  private[syntheke] val loc: (sourcecode.File, sourcecode.Line))(
  private[syntheke] val run: DomainView => Either[Violation, (domain.Value, Option[domain.Requirement])])
    extends DomainReadable[D]:
  private[syntheke] def module: ModuleId = id.module

final class NodeDomain[D <: Domain] private[syntheke] (
  val domain:                  D,
  val key:                     NodeDomainKey,
  private[syntheke] val owner: DesignOwner)
    extends DomainReadable[D],
      ReadToken:
  private[syntheke] def module:      ModuleId    = key.node.module
  private[syntheke] def tokenOwner:  DesignOwner = owner
