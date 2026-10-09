package me.jiuyang.syntheke

/** A boundary of a design: a port of its root, which `terminal`, a node of the root's boundary module, binds to. */
sealed abstract class Boundary[P <: Protocol] private[syntheke] (val protocol: P, private[syntheke] val terminal: Port[P]):
  /** The boundary's name on the design root. */
  def id: ModuleNodeId = ModuleNodeId(terminal.id.module.parent.get, terminal.id.name)

final class InwardBoundary[P <: Protocol] private[syntheke] (p: P, terminal: OutwardPort[P]) extends Boundary[P](p, terminal)

final class OutwardBoundary[P <: Protocol] private[syntheke] (p: P, terminal: InwardPort[P]) extends Boundary[P](p, terminal)
