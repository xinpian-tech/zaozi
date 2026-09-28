package me.jiuyang.syntheke

sealed abstract class Reader[T] private[syntheke] (
  private[syntheke] val node:           ModuleNodeId,
  private[syntheke] val nodeCapability: NodeCapability,
  private[syntheke] val owner0:         DesignOwner)
    extends ReadToken:
  type Value = T
  private[syntheke] def tokenOwner: DesignOwner = owner0

final class DownReader[T] private[syntheke] (node: ModuleNodeId, capability: NodeCapability, owner: DesignOwner)
    extends Reader[T](node, capability, owner)
final class UpReader[T] private[syntheke] (node: ModuleNodeId, capability: NodeCapability, owner: DesignOwner)
    extends Reader[T](node, capability, owner)

private[syntheke] final class NodeCapability

sealed abstract class NodeHandle[P <: Protocol] private[syntheke] (
  val protocol:                     P,
  val id:                           ModuleNodeId,
  private[syntheke] val owner:      DesignOwner,
  private[syntheke] val capability: NodeCapability,
  private[syntheke] val nodeDomains: Vector[NodeDomain[?]]):

  def domain[D <: Domain](domain: D): NodeDomain[D] =
    nodeDomains
      .find(_.domain eq domain)
      .getOrElse(throw new IllegalArgumentException(s"node ${id.show} has no ${domain.key.show} domain"))
      .asInstanceOf[NodeDomain[D]]

sealed abstract class NodeDraft[P <: Protocol] private[syntheke] (
  protocol0:                   P,
  id0:                         ModuleNodeId,
  private[syntheke] val scope: BuildContext[? <: GeneratorMode[?]],
  capability0:                 NodeCapability,
  nodeDomains0:                 Vector[NodeDomain[?]])
    extends NodeHandle[P](protocol0, id0, scope.owner, capability0, nodeDomains0)

final class InwardNodeDraft[P <: Protocol] private[syntheke] (
  protocol0: P,
  scope0:    BuildContext[? <: GeneratorMode[?]],
  id0:       ModuleNodeId,
  capability0: NodeCapability,
  nodeDomains0: Vector[NodeDomain[?]])
    extends NodeDraft[P](protocol0, id0, scope0, capability0, nodeDomains0):

  def seal(
    reads: ReadPlan
  )(f:     ReadValues => Either[Violation, (protocol.Up, Vector[Constraint])]
  ): InwardPort[P] =
    scope.seal(this, reads, f)

  def fixed(value: protocol.Up): InwardPort[P] = scope.fixed(this, value)

final class OutwardNodeDraft[P <: Protocol] private[syntheke] (
  protocol0: P,
  scope0:    BuildContext[? <: GeneratorMode[?]],
  id0:       ModuleNodeId,
  capability0: NodeCapability,
  nodeDomains0: Vector[NodeDomain[?]])
    extends NodeDraft[P](protocol0, id0, scope0, capability0, nodeDomains0):

  def seal(
    reads: ReadPlan
  )(f:     ReadValues => Either[Violation, (protocol.Down, Vector[Constraint])]
  ): OutwardPort[P] =
    scope.seal(this, reads, f)

  def fixed(value: protocol.Down): OutwardPort[P] = scope.fixed(this, value)

sealed abstract class Port[P <: Protocol] private[syntheke] (
  protocol0:   P,
  id0:         ModuleNodeId,
  owner0:      DesignOwner,
  capability0: NodeCapability,
  nodeDomains0: Vector[NodeDomain[?]])
    extends NodeHandle[P](protocol0, id0, owner0, capability0, nodeDomains0)

final class InwardPort[P <: Protocol] private[syntheke] (
  protocol0:   P,
  id0:         ModuleNodeId,
  owner0:      DesignOwner,
  capability0: NodeCapability,
  nodeDomains0: Vector[NodeDomain[?]])
    extends Port[P](protocol0, id0, owner0, capability0, nodeDomains0)

final class OutwardPort[P <: Protocol] private[syntheke] (
  protocol0:   P,
  id0:         ModuleNodeId,
  owner0:      DesignOwner,
  capability0: NodeCapability,
  nodeDomains0: Vector[NodeDomain[?]])
    extends Port[P](protocol0, id0, owner0, capability0, nodeDomains0)
