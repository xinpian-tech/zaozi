package me.jiuyang.syntheke

sealed abstract class NodeHandle[P <: Protocol] private[syntheke] (
  val protocol: P,
  val id:       ModuleNodeId,
  private[syntheke] val domains: Vector[Domain[?]]):

  /** The domain of this kind the node is in. */
  def domain[K <: DomainKind](
    kind:       K
  )(
    using file: sourcecode.File,
    line:       sourcecode.Line
  ): Domain[K] =
    domains
      .find(_.kind eq kind)
      .getOrElse(fail(s"node ${id.show} is in no ${kind.name} domain", (file, line)))
      .asInstanceOf[Domain[K]]

sealed abstract class NodeDraft[P <: Protocol] private[syntheke] (
  protocol0:                   P,
  id0:                         ModuleNodeId,
  private[syntheke] val scope: BuildContext[? <: GeneratorMode[?]],
  domains0:                    Vector[Domain[?]])
    extends NodeHandle[P](protocol0, id0, domains0)

final class InwardNodeDraft[P <: Protocol] private[syntheke] (
  protocol0: P,
  scope0:    BuildContext[? <: GeneratorMode[?]],
  id0:       ModuleNodeId,
  domains0:  Vector[Domain[?]])
    extends NodeDraft[P](protocol0, id0, scope0, domains0),
      ParameterValue:
  type Value = protocol.Down

final class OutwardNodeDraft[P <: Protocol] private[syntheke] (
  protocol0: P,
  scope0:    BuildContext[? <: GeneratorMode[?]],
  id0:       ModuleNodeId,
  domains0:  Vector[Domain[?]])
    extends NodeDraft[P](protocol0, id0, scope0, domains0),
      ParameterValue:
  type Value = protocol.Up

sealed abstract class Port[P <: Protocol] private[syntheke] (
  protocol0: P,
  id0:       ModuleNodeId,
  domains0:  Vector[Domain[?]])
    extends NodeHandle[P](protocol0, id0, domains0)

final class InwardPort[P <: Protocol] private[syntheke] (
  protocol0: P,
  id0:       ModuleNodeId,
  domains0:  Vector[Domain[?]])
    extends Port[P](protocol0, id0, domains0),
      ParameterValue:
  type Value = protocol.Down

final class OutwardPort[P <: Protocol] private[syntheke] (
  protocol0: P,
  id0:       ModuleNodeId,
  domains0:  Vector[Domain[?]])
    extends Port[P](protocol0, id0, domains0),
      ParameterValue:
  type Value = protocol.Up
