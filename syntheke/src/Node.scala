package me.jiuyang.syntheke

sealed abstract class NodeHandle[P <: Protocol] private[syntheke] (
  val protocol:                      P,
  val id:                            ModuleNodeId,
  private[syntheke] val kinds: Vector[DomainKind]):

  /** The domain of this kind the node is in. */
  def domain[K <: DomainKind](kind: K): DomainRef[K] =
    require(kinds.exists(_ eq kind), s"node ${id.show} is in no ${kind.name} domain")
    new DomainRef(kind, id)

sealed abstract class NodeDraft[P <: Protocol] private[syntheke] (
  protocol0:                   P,
  id0:                         ModuleNodeId,
  private[syntheke] val scope: BuildContext[? <: GeneratorMode[?]],
  kinds0:                      Vector[DomainKind])
    extends NodeHandle[P](protocol0, id0, kinds0)

final class InwardNodeDraft[P <: Protocol] private[syntheke] (
  protocol0:    P,
  scope0:       BuildContext[? <: GeneratorMode[?]],
  id0:          ModuleNodeId,
  kinds0:       Vector[DomainKind])
    extends NodeDraft[P](protocol0, id0, scope0, kinds0), ParameterValue:
  type Value = protocol.Down

final class OutwardNodeDraft[P <: Protocol] private[syntheke] (
  protocol0:    P,
  scope0:       BuildContext[? <: GeneratorMode[?]],
  id0:          ModuleNodeId,
  kinds0:       Vector[DomainKind])
    extends NodeDraft[P](protocol0, id0, scope0, kinds0), ParameterValue:
  type Value = protocol.Up

sealed abstract class Port[P <: Protocol] private[syntheke] (
  protocol0:    P,
  id0:          ModuleNodeId,
  kinds0:       Vector[DomainKind])
    extends NodeHandle[P](protocol0, id0, kinds0)

final class InwardPort[P <: Protocol] private[syntheke] (
  protocol0:    P,
  id0:          ModuleNodeId,
  kinds0:       Vector[DomainKind])
    extends Port[P](protocol0, id0, kinds0), ParameterValue:
  type Value = protocol.Down

final class OutwardPort[P <: Protocol] private[syntheke] (
  protocol0:    P,
  id0:          ModuleNodeId,
  kinds0:       Vector[DomainKind])
    extends Port[P](protocol0, id0, kinds0), ParameterValue:
  type Value = protocol.Up
