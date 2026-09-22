package me.jiuyang.syntheke

sealed abstract class Boundary[P <: Protocol] private[syntheke] (
  val protocol: P,
  val id: ModuleNodeId,
  private[syntheke] val terminal: Port[P])

final class InwardBoundary[P <: Protocol] private[syntheke] (
  p: P, id: ModuleNodeId, terminal: OutwardPort[P])
    extends Boundary[P](p, id, terminal)

final class OutwardBoundary[P <: Protocol] private[syntheke] (
  p: P, id: ModuleNodeId, terminal: InwardPort[P])
    extends Boundary[P](p, id, terminal)
