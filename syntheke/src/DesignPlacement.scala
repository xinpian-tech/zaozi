package me.jiuyang.syntheke

import scala.collection.immutable.VectorMap

/** A frozen design's boundaries and public probes at an instance path; the design itself stays definition-local. */
private[syntheke] final class DesignPlacement(definition: ResolvedDesign, val root: ModuleId):
  private def node(id: ModuleNodeId): ModuleNodeId = id.copy(module = ModuleId(root.path ++ id.module.path))

  private def place[P <: Protocol](boundary: Boundary[P]): Boundary[P] = boundary match
    case b: InwardBoundary[P]  =>
      val terminal = b.terminal.asInstanceOf[OutwardPort[P]]
      new InwardBoundary(new OutwardPort(terminal.protocol, node(terminal.id), terminal.domains))
    case b: OutwardBoundary[P] =>
      val terminal = b.terminal.asInstanceOf[InwardPort[P]]
      new OutwardBoundary(new InwardPort(terminal.protocol, node(terminal.id), terminal.domains))

  /** Each boundary of the frozen design, with the boundary standing for it at the instance. */
  val boundaries: VectorMap[Boundary[?], Boundary[?]] =
    VectorMap.from(definition.spec.boundaries.map(b => b -> place(b)))

  def inwardBoundary[P <: Protocol](value: InwardBoundary[P]):   InwardBoundary[P]  =
    boundaries(value).asInstanceOf[InwardBoundary[P]]
  def outwardBoundary[P <: Protocol](value: OutwardBoundary[P]): OutwardBoundary[P] =
    boundaries(value).asInstanceOf[OutwardBoundary[P]]

  /** The frozen design's public probes, read at the instance. */
  val probes: ProbeCatalog = new ProbeCatalog(definition.probes.nodes.map(placeProbe))

  private def placeProbe[P](probe: ResolvedProbe[P]): ResolvedProbe[P] =
    new ResolvedProbe(
      new ProbeNode(node(probe.id), probe.node.contract),
      probe.parameters,
      probe.port.copy(id = node(probe.port.id)),
      probe.implementation
    )
