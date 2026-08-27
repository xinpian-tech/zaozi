// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

import scala.collection.immutable.SortedSet

/** Thrown by [[Negotiator.negotiate]] at the first error found. The message states the problem directly, with the
  * stable identifiers of the subjects, the relevant source locations, and parameter snapshots where applicable (doc @sec-error-semantics).
  */
final class NegotiationException(message: String) extends RuntimeException(message)

/** The Negotiate phase (doc @ch-negotiation).
  *
  * Pure computation from [[DesignSpec]] to [[ResolvedDesign]]: structural checking, stable topological ordering, `Down`
  * forward propagation and `Up` backward propagation, per-edge settlement, cross-protocol reference resolution,
  * [[EdgeView]] assembly, generator parameter computation, and cross-hierarchy planning.
  *
  * Fail fast: the first error found is thrown as a [[NegotiationException]] on the spot. Protocol and port parameter
  * functions still report conflicts as values (`Left`); the negotiator turns them into the throw. Exceptions escaping
  * user code are bugs in that code and propagate untouched. Checks live only where the builder and the type system
  * cannot reach: name uniqueness, dependency well-formedness and reference targets are enforced at declaration; bind
  * ends share one protocol object by construction.
  */
object Negotiator:

  def negotiate(spec: DesignSpec): ResolvedDesign =
    val order                  = structuralCheck(spec)
    val propagated             = propagate(spec, order)
    val edges                  = settle(spec, propagated)
    val generators             = assembleViews(spec, edges)
    val (ports, wires, layers) = Planner.plan(spec, edges)
    ResolvedDesign(
      spec = spec,
      edges = edges,
      generatorModules = generators,
      portPlans = ports,
      wirePlans = wires,
      layerDecls = layers
    )

  private def fail(message: String): Nothing = throw NegotiationException(message)

  private def at(locs: (sourcecode.File, sourcecode.Line)*):        String = locs.map(_.show).mkString(", ")
  private def at(locs: Vector[(sourcecode.File, sourcecode.Line)]): String = locs.map(_.show).mkString(", ")

  // ============ pass 1: structural check and stable topological order ============

  /** The stable topological order of the Down parameter-dependency DAG; Up uses its reverse. */
  private final case class TopoOrder(nodes: Vector[ModuleNodeId])

  private def structuralCheck(spec: DesignSpec): TopoOrder =
    // Generator registry: one name — one entry. The name keys module naming, dedup and linking, so two distinct
    // entries sharing it would collide in the flat symbol namespace.
    spec.generators.groupBy(_.name).foreach { (name, entries) =>
      if entries.sizeIs > 1 then fail(s"generator name '$name' used by ${entries.size} distinct registry entries")
    }

    // Design binds : endpoint existence (builders can leak across Design builds), declaration-site ancestry,
    // and the exactly-once discipline. Directions and protocol equality hold by construction.
    spec.binds.foreach { b =>
      if spec.nodeSpec(b.source).isEmpty then
        fail(s"bind source ${b.source.show} is not a node of this design, at ${at(b.loc)}")
      if spec.nodeSpec(b.target).isEmpty then
        fail(s"bind target ${b.target.show} is not a node of this design, at ${at(b.loc)}")
      if !(b.declaredIn.isAncestorOf(b.source.module) && b.declaredIn.isAncestorOf(b.target.module)) then
        fail(
          s"bind ${b.source.show} -> ${b.target.show} declared in ${b.declaredIn.show}, " +
            s"which is not an ancestor of both endpoints, at ${at(b.loc)}"
        )
    }
    val asSource = spec.binds.groupBy(_.source)
    val asTarget = spec.binds.groupBy(_.target)
    spec.generatorModules.foreach { g =>
      g.nodes.foreach { n =>
        val id            = ModuleNodeId(g.id, n.name)
        val (role, binds) = n.direction match
          case NodeDirection.Outward => ("source", asSource.get(id))
          case NodeDirection.Inward  => ("target", asTarget.get(id))
        val count         = binds.fold(0)(_.size)
        if count != 1 then
          fail(
            s"${n.direction.toString.toLowerCase} node ${id.show} is the $role of $count binds, " +
              s"expected exactly 1, at ${at(binds.fold(Vector(n.loc))(_.map(_.loc)) :+ n.loc)}"
          )
      }
    }

    // Stable topological sort of the Down DAG; a cycle is an error. Kahn over immutable state, ties broken by module
    // preorder then node declaration order.
    val preorder = spec.moduleOrder.zipWithIndex.toMap
    val nodeKey  = (
      for g <- spec.generatorModules; n <- g.nodes
      yield ModuleNodeId(g.id, n.name) -> (preorder(g.id), n.order)
    ).toMap
    val nodeIds  = nodeKey.keys.toVector
    val edges    = spec.binds.map(b => b.source -> b.target) ++ (
      for g <- spec.generatorModules; d <- g.dependencies
      yield ModuleNodeId(g.id, d.from) -> ModuleNodeId(g.id, d.to)
    )

    val successors   = edges.groupMap(_._1)(_._2).withDefaultValue(Vector.empty)
    val predecessors = edges.groupMap(_._2)(_._1).withDefaultValue(Vector.empty)
    val indegree     = nodeIds.map(id => id -> predecessors(id).size).toMap

    given Ordering[ModuleNodeId] = Ordering.by(nodeKey)

    @annotation.tailrec
    def kahn(
      ready: SortedSet[ModuleNodeId],
      indeg: Map[ModuleNodeId, Int],
      acc:   Vector[ModuleNodeId]
    ): Vector[ModuleNodeId] =
      ready.headOption match
        case None    => acc
        case Some(n) =>
          val (indeg2, unblocked) = successors(n).foldLeft((indeg, Vector.empty[ModuleNodeId])) { case ((m, rs), s) =>
            val c = m(s) - 1
            (m.updated(s, c), if c == 0 then rs :+ s else rs)
          }
          kahn(ready - n ++ unblocked, indeg2, acc :+ n)

    val sorted = kahn(SortedSet.from(nodeIds.filter(indegree(_) == 0)), indegree, Vector.empty)
    if sorted.size < nodeIds.size then
      // Shrink to the cycles themselves: drop nodes without both a predecessor and a successor inside the
      // remainder, so nodes merely blocked downstream of a cycle are not reported as part of it.
      @annotation.tailrec
      def shrink(s: Set[ModuleNodeId]): Set[ModuleNodeId] =
        val s2 = s.filter(id => successors(id).exists(s) && predecessors(id).exists(s))
        if s2 == s then s else shrink(s2)
      val onCycle = shrink(nodeIds.toSet -- sorted)
      val members = nodeIds.filter(onCycle).sortBy(nodeKey)
      val locs    = members.flatMap(id => spec.nodeSpec(id).map(_.loc)) ++
        spec.binds.filter(b => onCycle(b.source) && onCycle(b.target)).map(_.loc) ++
        spec.generatorModules.flatMap(g =>
          g.dependencies
            .filter(d => onCycle(ModuleNodeId(g.id, d.from)) && onCycle(ModuleNodeId(g.id, d.to)))
            .map(_.loc)
        )
      fail(
        s"parameter dependency graph has a cycle through ${members.map(_.show).mkString(", ")}, at ${at(locs)}"
      )
    TopoOrder(sorted)

  // ============ pass 2: Down forward propagation and Up backward propagation ============

  private final case class Propagated(down: Map[ModuleNodeId, Any], up: Map[ModuleNodeId, Any])

  private def propagate(spec: DesignSpec, order: TopoOrder): Propagated =
    val bindOfSource = spec.binds.map(b => b.source -> b).toMap
    val bindOfTarget = spec.binds.map(b => b.target -> b).toMap

    def modOf(id:  ModuleNodeId): GeneratorModuleSpec = spec.generatorModule(id.module).get
    def specOf(id: ModuleNodeId): NodeSpec            = spec.nodeSpec(id).get

    /** Declared reads of a node's function and the dependency declarations that grant them, in node declaration order:
      * pred inward nodes for a dFn, succ outward nodes for a uFn.
      */
    def readsOf(id: ModuleNodeId, direction: NodeDirection): Vector[(ModuleNodeId, ParamDependencySpec)] =
      val g    = modOf(id)
      val name = id.name
      val deps = direction match
        case NodeDirection.Outward => g.dependencies.filter(_.to == name).map(d => (ModuleNodeId(g.id, d.from), d))
        case NodeDirection.Inward  => g.dependencies.filter(_.from == name).map(d => (ModuleNodeId(g.id, d.to), d))
      deps.sortBy((n, _) => g.node(n.name).get.order)

    def evaluate(values: Map[ModuleNodeId, Any], id: ModuleNodeId, downSide: Boolean): Any =
      val n     = specOf(id)
      val reads = readsOf(id, n.direction)
      n.fn(reads.map((r, _) => r -> values(r)).toMap) match
        case Right(v)        => v
        case Left(violation) =>
          val snapshot = reads.map { (r, _) =>
            val p  = specOf(r).protocol
            val rw = (if downSide then p.downRW else p.upRW).asInstanceOf[upickle.default.ReadWriter[Any]]
            s"${r.show}=${ujson.write(
                upickle.default.writeJs(values(r))(
                  using rw
                )
              )}"
          }
          fail(
            s"propagation failed at ${id.show} (${n.direction}): ${violation.message}; " +
              s"inputs [${snapshot.mkString(", ")}], at ${at(n.loc +: reads.map(_._2.loc))}"
          )

    // Down: forward over the topological order. Outward nodes evaluate dFn; inward nodes receive along their bind.
    val down = order.nodes.foldLeft(Map.empty[ModuleNodeId, Any]) { (values, id) =>
      values.updated(
        id,
        specOf(id).direction match
          case NodeDirection.Outward => evaluate(values, id, downSide = true)
          case NodeDirection.Inward  => values(bindOfTarget(id).source)
      )
    }

    // Up: backward over the same order. Inward nodes evaluate uFn; outward nodes receive along their bind.
    val up = order.nodes.reverse.foldLeft(Map.empty[ModuleNodeId, Any]) { (values, id) =>
      values.updated(
        id,
        specOf(id).direction match
          case NodeDirection.Inward  => evaluate(values, id, downSide = false)
          case NodeDirection.Outward => values(bindOfSource(id).target)
      )
    }

    Propagated(down, up)

  // ============ pass 3: per-edge settlement ============

  private def settle(spec: DesignSpec, prop: Propagated): Vector[ResolvedEdge] =
    spec.binds.map { b =>
      val p    = spec.nodeSpec(b.source).get.protocol
      val down = prop.down(b.source)
      val up   = prop.up(b.target)
      p.asInstanceOf[Protocol { type Down = Any; type Up = Any }].negotiate(down, up) match
        case Left(violation) =>
          fail(s"settle failed at ${b.bindId.show}: ${violation.message}, at ${at(b.loc)}")
        case Right(edge)     =>
          val bundle = p.asInstanceOf[Protocol { type Edge = Any }].interfaceOf(edge)
          // Probes belong to verification protocols; design ports are plain data (no open aggregates in hardware).
          ProtocolBundle.leaves(bundle).collectFirst { case (path, _: ProtocolInterface.Probe) => path }.foreach {
            path =>
              fail(
                s"design interface of ${b.bindId.show} contains a Probe at ${path.show}; " +
                  s"probes belong to verification protocols, at ${at(b.loc)}"
              )
          }
          ResolvedEdge(b.bindId, p, down, up, edge, bundle)
    }

  // ============ pass 4: cross-protocol references, EdgeView assembly, generator parameters ============

  private def assembleViews(spec: DesignSpec, edges: Vector[ResolvedEdge]): Vector[ResolvedGeneratorModule] =
    val edgeOfSource = edges.map(e => e.bind.source -> e).toMap
    val edgeOfTarget = edges.map(e => e.bind.target -> e).toMap

    spec.generatorModules.map { g =>
      val nodeViews = g.nodes.map { n =>
        val id   = ModuleNodeId(g.id, n.name)
        val edge = n.direction match
          case NodeDirection.Outward => edgeOfSource(id)
          case NodeDirection.Inward  => edgeOfTarget(id)
        // Reference targets are same-module existing nodes of the expected protocol by construction (builder).
        val refs = n.refs.map { r =>
          val ts         = g.node(r.target.name).get
          val targetEdge = ts.direction match
            case NodeDirection.Outward => edgeOfSource(r.target)
            case NodeDirection.Inward  => edgeOfTarget(r.target)
          ResolvedProtocolReference(r.refName, id, r.target, ts.protocol, targetEdge.edge)
        }
        NodeView(id, n.direction, edge, refs)
      }

      val view = EdgeView(g.id, nodeViews)
      g.computeFullParam(view) match
        case Left(violation) =>
          fail(s"capability exceeded at ${g.id.show}: ${violation.message}, at ${at(g.loc)}")
        case Right(fp)       =>
          val encoded = upickle.default.writeJs(fp)(
            using g.entry.fullParamRW.asInstanceOf[upickle.default.ReadWriter[Any]]
          )
          ResolvedGeneratorModule(g.id, g.entry, view, fp, encoded)
    }
