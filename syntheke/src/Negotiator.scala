// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

import scala.collection.mutable

/** The Negotiate phase (doc @ch-negotiation).
  *
  * Pure computation from [[DesignSpec]] to [[ResolvedDesign]]: structural checking, stable topological ordering, `Down`
  * forward propagation and `Up` backward propagation, per-edge and per-sink settlement, cross-protocol reference
  * resolution, [[EdgeView]] assembly, generator parameter computation, and cross-hierarchy planning.
  *
  * Errors are values: every failure the current pass can determine is collected, normalized, and returned in bulk;
  * later passes do not run (doc @sec-error-semantics).
  */
object Negotiator:

  def negotiate(spec: DesignSpec): Either[Vector[NegotiationError], ResolvedDesign] =
    for
      order       <- structuralCheck(spec)
      propagation <- propagate(spec, order)
      settled     <- settle(spec, propagation)
      generators  <- assembleViews(spec, settled)
    yield
      val (ports, wires, layers) = Planner.plan(spec, settled)
      ResolvedDesign(
        spec = spec,
        edges = settled.edges,
        dvGroups = settled.dvGroups,
        generatorModules = generators,
        portPlans = ports,
        wirePlans = wires,
        layerDecls = layers
      )

  // ============ pass 1: structural check and stable topological order ============

  /** The stable topological order of the Down parameter-dependency DAG; Up uses its reverse. */
  private final case class TopoOrder(nodes: Vector[ModuleNodeId])

  private def structuralCheck(spec: DesignSpec): Either[Vector[NegotiationError], TopoOrder] =
    val errors = mutable.ArrayBuffer.empty[NegotiationError]

    // Protocol registry: one ProtocolId — one object; kind must match the object's flavor (N1).
    spec.protocols.groupBy(_._1).foreach { (pid, regs) =>
      if regs.map(_._2).distinct.sizeIs > 1 then
        errors += NegotiationError.ProtocolMismatch(
          s"protocol id ${pid.show} declared by ${regs.size} distinct protocol objects",
          Vector(pid),
          Vector.empty,
          Vector.empty
        )
    }
    spec.protocols.foreach { (pid, obj) =>
      val kindOk = obj match
        case _: Protocol   => pid.kind == ProtocolKind.Design
        case _: DVProtocol => pid.kind == ProtocolKind.Verification
        case _ => false
      if !kindOk then
        errors += NegotiationError.ProtocolMismatch(
          s"protocol ${pid.show} has kind ${pid.kind} inconsistent with its object flavor",
          Vector(pid),
          Vector.empty,
          Vector.empty
        )
    }

    // Generator registry: one GeneratorId — one entry (N10).
    spec.generators.groupBy(_.id).foreach { (gid, entries) =>
      if entries.sizeIs > 1 then errors += NegotiationError.GeneratorConflict(gid, Vector.empty)
    }

    // Names (N9): duplicate child instance names; duplicate node / endpoint names within a module.
    spec.modules.values.foreach {
      case w: WrapperModuleSpec   =>
        w.children.groupBy(identity).foreach { (name, occurrences) =>
          if occurrences.sizeIs > 1 then
            errors += NegotiationError.IllegalStructure(
              s"duplicate child instance name '$name' in ${w.id.show}",
              Vector((w.id / name).show),
              Vector(w.loc)
            )
        }
      case g: GeneratorModuleSpec =>
        val names = g.nodes.map(n => n.name -> n.loc) ++ g.dvSources.map(s => s.name -> s.loc) ++
          g.dvSinks.map(s => s.name -> s.loc)
        names.groupBy(_._1).foreach { (name, occurrences) =>
          if occurrences.sizeIs > 1 then
            errors += NegotiationError.IllegalStructure(
              s"duplicate endpoint name '$name' in ${g.id.show}",
              Vector(ModuleNodeId(g.id, name).show),
              occurrences.map(_._2)
            )
        }
    }

    // Module-internal parameter dependencies (N9): endpoints must exist with the right directions; no duplicates.
    spec.modules.values.foreach {
      case g: GeneratorModuleSpec =>
        g.dependencies.foreach { d =>
          val from = g.node(d.from)
          val to   = g.node(d.to)
          if !from.exists(_.direction == NodeDirection.Inward) then
            errors += NegotiationError.IllegalStructure(
              s"dependency source '${d.from}' of ${g.id.show} is not an inward node",
              Vector(ModuleNodeId(g.id, d.from).show),
              Vector(d.loc)
            )
          if !to.exists(_.direction == NodeDirection.Outward) then
            errors += NegotiationError.IllegalStructure(
              s"dependency target '${d.to}' of ${g.id.show} is not an outward node",
              Vector(ModuleNodeId(g.id, d.to).show),
              Vector(d.loc)
            )
        }
        g.dependencies.groupBy(d => (d.from, d.to)).foreach { (pair, occurrences) =>
          if occurrences.sizeIs > 1 then
            errors += NegotiationError.IllegalStructure(
              s"duplicate parameter dependency ${pair._1} -> ${pair._2} in ${g.id.show}",
              Vector(ModuleNodeId(g.id, pair._1).show, ModuleNodeId(g.id, pair._2).show),
              occurrences.map(_.loc)
            )
        }
      case _ => ()
    }

    // Design binds (N4, N1).
    spec.binds.foreach { b =>
      val source = spec.nodeSpec(b.source)
      val target = spec.nodeSpec(b.target)
      if !source.exists(_.direction == NodeDirection.Outward) then
        errors += NegotiationError.IllegalBind(
          s"bind source ${b.source.show} is not an existing outward node",
          Vector(b.source),
          Vector(b.bindId),
          Vector(b.loc)
        )
      if !target.exists(_.direction == NodeDirection.Inward) then
        errors += NegotiationError.IllegalBind(
          s"bind target ${b.target.show} is not an existing inward node",
          Vector(b.target),
          Vector(b.bindId),
          Vector(b.loc)
        )
      if !(b.declaredIn.isAncestorOf(b.source.module) && b.declaredIn.isAncestorOf(b.target.module)) then
        errors += NegotiationError.IllegalBind(
          s"bind declared in ${b.declaredIn.show} which is not an ancestor of both endpoints",
          Vector(b.source, b.target),
          Vector(b.bindId),
          Vector(b.loc)
        )
      for s <- source; t <- target do
        if s.protocol.id != t.protocol.id then
          errors += NegotiationError.ProtocolMismatch(
            s"bind endpoints use different protocols",
            Vector(s.protocol.id, t.protocol.id),
            Vector(b.source, b.target),
            Vector(b.loc)
          )
    }

    // Every outward node exactly once a bind source, every inward node exactly once a bind target (N4).
    val asSource = spec.binds.groupBy(_.source)
    val asTarget = spec.binds.groupBy(_.target)
    spec.generatorModules.foreach { g =>
      g.nodes.foreach { n =>
        val id = ModuleNodeId(g.id, n.name)
        n.direction match
          case NodeDirection.Outward =>
            val count = asSource.get(id).fold(0)(_.size)
            if count != 1 then
              errors += NegotiationError.IllegalBind(
                s"outward node ${id.show} is the source of $count binds, expected exactly 1",
                Vector(id),
                asSource.get(id).fold(Vector.empty[BindId])(_.map(_.bindId)),
                asSource.get(id).fold(Vector(n.loc))(_.map(_.loc)) :+ n.loc
              )
          case NodeDirection.Inward  =>
            val count = asTarget.get(id).fold(0)(_.size)
            if count != 1 then
              errors += NegotiationError.IllegalBind(
                s"inward node ${id.show} is the target of $count binds, expected exactly 1",
                Vector(id),
                asTarget.get(id).fold(Vector.empty[BindId])(_.map(_.bindId)),
                asTarget.get(id).fold(Vector(n.loc))(_.map(_.loc)) :+ n.loc
              )
      }
    }

    // Verification topology (N8).
    val dvSourceSpecs = spec.generatorModules
      .flatMap(g => g.dvSources.map(s => DVSourceId(g.id, s.name) -> (g, s)))
      .toMap
    val dvSinkSpecs   = spec.generatorModules
      .flatMap(g => g.dvSinks.map(s => DVSinkId(g.id, s.name) -> (g, s)))
      .toMap
    spec.dvBinds.foreach { b =>
      if !dvSourceSpecs.contains(b.source) then
        errors += NegotiationError.IllegalVerification(
          s"probe source ${b.source.show} does not exist",
          Vector(b.source),
          Vector(b.sink),
          Vector(b.loc)
        )
      if !dvSinkSpecs.contains(b.sink) then
        errors += NegotiationError.IllegalVerification(
          s"probe sink ${b.sink.show} does not exist",
          Vector(b.source),
          Vector(b.sink),
          Vector(b.loc)
        )
      if !(b.declaredIn.isAncestorOf(b.source.module) && b.declaredIn.isAncestorOf(b.sink.module)) then
        errors += NegotiationError.IllegalVerification(
          s"verification bind declared in ${b.declaredIn.show} which is not an ancestor of both endpoints",
          Vector(b.source),
          Vector(b.sink),
          Vector(b.loc)
        )
      for (_, src) <- dvSourceSpecs.get(b.source); (_, snk) <- dvSinkSpecs.get(b.sink) do
        if src.protocol.id != snk.protocol.id then
          errors += NegotiationError.IllegalVerification(
            s"probe source and sink use different protocols: ${src.protocol.id.show} vs ${snk.protocol.id.show}",
            Vector(b.source),
            Vector(b.sink),
            Vector(b.loc, src.loc, snk.loc)
          )
      for (sinkModule, _) <- dvSinkSpecs.get(b.sink); (sourceModule, _) <- dvSourceSpecs.get(b.source) do
        val w = sinkModule.id.parent
        if !w.exists(_.isStrictAncestorOf(sourceModule.id)) then
          errors += NegotiationError.IllegalVerification(
            s"parent of sink generator ${sinkModule.id.show} is not a strict ancestor of source module ${sourceModule.id.show}",
            Vector(b.source),
            Vector(b.sink),
            Vector(b.loc)
          )
    }
    // Each source exactly one bind; each sink at least one.
    val dvBySource    = spec.dvBinds.groupBy(_.source)
    dvSourceSpecs.foreach { (id, gs) =>
      val count = dvBySource.get(id).fold(0)(_.size)
      if count != 1 then
        errors += NegotiationError.IllegalVerification(
          s"probe source ${id.show} has $count verification binds, expected exactly 1",
          Vector(id),
          Vector.empty,
          dvBySource.get(id).fold(Vector(gs._2.loc))(_.map(_.loc)) :+ gs._2.loc
        )
    }
    val dvBySink      = spec.dvBinds.groupBy(_.sink)
    dvSinkSpecs.foreach { (id, gs) =>
      if !dvBySink.contains(id) then
        errors += NegotiationError.IllegalVerification(
          s"probe sink ${id.show} collects no probe source",
          Vector.empty,
          Vector(id),
          Vector(gs._2.loc)
        )
    }

    if errors.nonEmpty then return Left(NegotiationError.normalize(errors.toVector))

    // Stable topological sort of the Down DAG (N9 on cycles).
    val preorder   = spec.moduleOrder.zipWithIndex.toMap
    val nodeOrder  = mutable.Map.empty[ModuleNodeId, (Int, Int)]
    val successors = mutable.Map.empty[ModuleNodeId, Vector[ModuleNodeId]].withDefaultValue(Vector.empty)
    val indegree   = mutable.Map.empty[ModuleNodeId, Int].withDefaultValue(0)
    val allNodes   = mutable.ArrayBuffer.empty[ModuleNodeId]
    spec.generatorModules.foreach { g =>
      g.nodes.foreach { n =>
        val id = ModuleNodeId(g.id, n.name)
        allNodes += id
        nodeOrder(id) = (preorder(g.id), n.order)
      }
    }
    def addEdge(from: ModuleNodeId, to: ModuleNodeId): Unit =
      successors(from) = successors(from) :+ to
      indegree(to) = indegree(to) + 1
    spec.binds.foreach(b => addEdge(b.source, b.target))
    spec.generatorModules.foreach { g =>
      g.dependencies.foreach(d => addEdge(ModuleNodeId(g.id, d.from), ModuleNodeId(g.id, d.to)))
    }

    given Ordering[ModuleNodeId] = Ordering.by(nodeOrder)
    val ready                    = mutable.SortedSet.from(allNodes.filter(indegree(_) == 0))
    val sorted                   = mutable.ArrayBuffer.empty[ModuleNodeId]
    while ready.nonEmpty do
      val n = ready.head
      ready -= n
      sorted += n
      successors(n).foreach { s =>
        indegree(s) = indegree(s) - 1
        if indegree(s) == 0 then ready += s
      }
    if sorted.size < allNodes.size then
      val remaining = allNodes.filterNot(sorted.contains).toVector
      val locs      = remaining.flatMap(id => spec.nodeSpec(id).map(_.loc)) ++
        spec.binds.filter(b => remaining.contains(b.source) && remaining.contains(b.target)).map(_.loc)
      Left(
        Vector(
          NegotiationError.IllegalStructure(
            s"parameter dependency graph has a cycle through ${remaining.map(_.show).mkString(", ")}",
            remaining.map(_.show),
            locs
          )
        )
      )
    else Right(TopoOrder(sorted.toVector))

  // ============ pass 2: Down forward propagation and Up backward propagation ============

  private final case class Propagated(down: Map[ModuleNodeId, Any], up: Map[ModuleNodeId, Any])

  private def propagate(spec: DesignSpec, order: TopoOrder): Either[Vector[NegotiationError], Propagated] =
    val errors = mutable.ArrayBuffer.empty[NegotiationError]

    val bindOfSource = spec.binds.map(b => b.source -> b).toMap
    val bindOfTarget = spec.binds.map(b => b.target -> b).toMap

    def modOf(id:  ModuleNodeId): GeneratorModuleSpec = spec.generatorModule(id.module).get
    def specOf(id: ModuleNodeId): NodeSpec            = spec.nodeSpec(id).get

    /** Declared reads of a node's function: pred inward nodes for a dFn, succ outward nodes for a uFn, in node
      * declaration order.
      */
    def readsOf(id: ModuleNodeId, direction: NodeDirection): Vector[ModuleNodeId] =
      val g    = modOf(id)
      val name = id.name
      val deps = direction match
        case NodeDirection.Outward => g.dependencies.filter(_.to == name).map(_.from)
        case NodeDirection.Inward  => g.dependencies.filter(_.from == name).map(_.to)
      deps.map(n => ModuleNodeId(g.id, n)).sortBy(n => g.node(n.name).get.order)

    def snapshot(reads: Vector[ModuleNodeId], values: Map[ModuleNodeId, Any], downSide: Boolean): Vector[ujson.Value] =
      reads.map { r =>
        val p     = specOf(r).protocol
        val codec = (if downSide then p.downCodec else p.upCodec).asInstanceOf[Codec[Any]]
        codec.encode(values(r))
      }

    def evaluate(
      values:   mutable.Map[ModuleNodeId, Any],
      blocked:  mutable.Set[ModuleNodeId],
      id:       ModuleNodeId,
      reads:    Vector[ModuleNodeId],
      downSide: Boolean
    ): Unit =
      val n = specOf(id)
      if reads.exists(blocked) then blocked += id
      else
        val input  = reads.map(r => r -> values(r)).toMap
        val result =
          try n.fn(input)
          catch case e: UndeclaredReadException => Left(PropagationViolation(e.getMessage))
        result match
          case Right(v)        => values(id) = v
          case Left(violation) =>
            blocked += id
            errors += NegotiationError.PropagationFailed(
              module = id.module,
              node = id,
              direction = if downSide then NodeDirection.Outward else NodeDirection.Inward,
              deps = reads,
              inputs = snapshot(reads, values.toMap, downSide),
              violation = violation,
              locs = Vector(n.loc)
            )

    // Down: forward over the topological order. Outward nodes evaluate dFn; inward nodes receive along their bind.
    val down        = mutable.Map.empty[ModuleNodeId, Any]
    val downBlocked = mutable.Set.empty[ModuleNodeId]
    order.nodes.foreach { id =>
      specOf(id).direction match
        case NodeDirection.Outward =>
          evaluate(down, downBlocked, id, readsOf(id, NodeDirection.Outward), downSide = true)
        case NodeDirection.Inward  =>
          val b = bindOfTarget(id)
          if downBlocked(b.source) then downBlocked += id
          else down(id) = down(b.source)
    }

    // Up: backward over the same order. Inward nodes evaluate uFn; outward nodes receive along their bind.
    val up        = mutable.Map.empty[ModuleNodeId, Any]
    val upBlocked = mutable.Set.empty[ModuleNodeId]
    order.nodes.reverseIterator.foreach { id =>
      specOf(id).direction match
        case NodeDirection.Inward  =>
          evaluate(up, upBlocked, id, readsOf(id, NodeDirection.Inward), downSide = false)
        case NodeDirection.Outward =>
          val b = bindOfSource(id)
          if upBlocked(b.target) then upBlocked += id
          else up(id) = up(b.target)
    }

    if errors.nonEmpty then Left(NegotiationError.normalize(errors.toVector))
    else Right(Propagated(down.toMap, up.toMap))

  // ============ pass 3: per-edge settlement and per-sink verification settlement ============

  private[syntheke] final case class Settled(
    edges:    Vector[ResolvedEdge],
    dvGroups: Vector[ResolvedDVGroup])

  private def settle(spec: DesignSpec, prop: Propagated): Either[Vector[NegotiationError], Settled] =
    val errors = mutable.ArrayBuffer.empty[NegotiationError]

    val edges = spec.binds.flatMap { b =>
      val p    = spec.nodeSpec(b.source).get.protocol
      val down = prop.down(b.source)
      val up   = prop.up(b.target)
      p.asInstanceOf[Protocol { type Down = Any; type Up = Any }].negotiate(down, up) match
        case Left(violation) =>
          errors += NegotiationError.SettleFailed(SettleSubject.Design(b.bindId), violation, Vector(b.loc))
          None
        case Right(edge)     =>
          try
            val bundle = p.asInstanceOf[Protocol { type Edge = Any }].interfaceOf(edge)
            Some(ResolvedEdge(b.bindId, p, down, up, edge, bundle))
          catch
            case e: IllegalArgumentException =>
              errors += NegotiationError.InterfaceViolation(
                s"interfaceOf returned an illegal ProtocolBundle: ${e.getMessage}",
                SettleSubject.Design(b.bindId),
                Vector(b.loc)
              )
              None
    }

    val dvGroups = spec.dvBinds
      .groupBy(_.sink)
      .toVector
      .sortBy(_._2.map(_.order).min)
      .flatMap { (sinkId, binds0) =>
        val binds    = binds0.sortBy(_.order)
        val sinkSpec = spec.generatorModule(sinkId.module).get.dvSinks.find(_.name == sinkId.name).get
        val p        = sinkSpec.protocol.asInstanceOf[DVProtocol { type Down = Any; type Edge = Any }]
        val sources  =
          binds.map(b => spec.generatorModule(b.source.module).get.dvSources.find(_.name == b.source.name).get)
        val downs    = sources.map(_.down)
        val layers   = sources.map(_.layer)
        p.resolve(downs) match
          case Left(violation) =>
            errors += NegotiationError.SettleFailed(SettleSubject.Verification(sinkId), violation, binds.map(_.loc))
            None
          case Right(edge)     =>
            p.interfacesOf(edge, layers) match
              case Left(violation)   =>
                errors += NegotiationError.SettleFailed(SettleSubject.Verification(sinkId), violation, binds.map(_.loc))
                None
              case Right(interfaces) =>
                checkDVInterfaces(interfaces, layers) match
                  case Some(detail) =>
                    errors += NegotiationError.InterfaceViolation(
                      detail,
                      SettleSubject.Verification(sinkId),
                      binds.map(_.loc)
                    )
                    None
                  case None         =>
                    Some(
                      ResolvedDVGroup(sinkId, sinkSpec.protocol, binds.map(_.bindId), downs, layers, edge, interfaces)
                    )
      }

    if errors.nonEmpty then Left(NegotiationError.normalize(errors.toVector))
    else Right(Settled(edges, dvGroups))

  /** DVInterfaces contract (doc @sec-dv-protocol); returns the first violation found. */
  private def checkDVInterfaces(interfaces: DVInterfaces, layers: Vector[LayerPath]): Option[String] =
    import scala.util.boundary, boundary.break
    boundary[Option[String]]:
      def fail(detail: String): Nothing = break(Some(detail))

      val n = layers.size
      if interfaces.sources.size != n || interfaces.sinkPaths.size != n then
        fail(s"expected $n sources and sinkPaths, got ${interfaces.sources.size} and ${interfaces.sinkPaths.size}")

      def flipsClear(tpe: ProtocolInterface): Boolean = tpe match
        case ProtocolInterface.Bundle(fields) => fields.forall(f => !f.flip && flipsClear(f.tpe))
        case ProtocolInterface.Vec(_, e)      => flipsClear(e)
        case ProtocolInterface.Probe(i, _)    => flipsClear(i)
        case _                                => true

      def leavesProbesWith(tpe: ProtocolInterface, layer: LayerPath): Option[String] =
        ProtocolBundle.leaves(tpe).collectFirst {
          case (path, ProtocolInterface.Probe(_, l)) if l != layer         =>
            s"probe at ${path.show} carries layer ${l.show}, expected ${layer.show}"
          case (path, leaf) if !leaf.isInstanceOf[ProtocolInterface.Probe] =>
            s"leaf at ${path.show} is not a Probe"
        }

      if !flipsClear(interfaces.sink) then fail("sink interface contains flipped fields")
      interfaces.sources.zipWithIndex.foreach { (s, i) =>
        if !flipsClear(s) then fail(s"sources($i) contains flipped fields")
        leavesProbesWith(s, layers(i)).foreach(v => fail(s"sources($i): $v"))
      }

      // Paths: valid, land on a Bundle, pairwise distinct and non-overlapping.
      val resolvedPaths  = interfaces.sinkPaths.zipWithIndex.map { (path, i) =>
        InterfacePath.resolve(interfaces.sink, path) match
          case Some(b: ProtocolInterface.Bundle) => (i, path, b)
          case Some(_)                           => fail(s"sinkPaths($i) ${path.show} does not land on a Bundle")
          case None                              => fail(s"sinkPaths($i) ${path.show} is invalid in the sink interface")
      }
      resolvedPaths.combinations(2).foreach { pair =>
        val Seq((i, a, _), (j, b, _)) = pair: @unchecked
        if a == b then fail(s"sinkPaths($i) and sinkPaths($j) are identical")
        if a.isPrefixOf(b) || b.isPrefixOf(a) then fail(s"sinkPaths($i) and sinkPaths($j) overlap")
      }
      // Structural equality with the source interface; per-source layer on the selected subtree.
      resolvedPaths.foreach { (i, path, bundle) =>
        if bundle != interfaces.sources(i) then
          fail(s"sink Bundle at ${path.show} differs structurally from sources($i)")
        leavesProbesWith(bundle, layers(i)).foreach(v => fail(s"sink subtree at ${path.show}: $v"))
      }
      // Exact cover: non-overlapping subtrees whose leaf count equals the sink leaf count.
      val selectedLeaves = resolvedPaths.map((_, _, b) => ProtocolBundle.leaves(b).size).sum
      val sinkLeaves     = ProtocolBundle.leaves(interfaces.sink).size
      if selectedLeaves != sinkLeaves then
        fail(s"selected Bundles cover $selectedLeaves signal leaves, sink has $sinkLeaves")
      None

  // ============ pass 4: cross-protocol references, EdgeView assembly, generator parameters ============

  private def assembleViews(
    spec:    DesignSpec,
    settled: Settled
  ): Either[Vector[NegotiationError], Vector[ResolvedGeneratorModule]] =
    val errors = mutable.ArrayBuffer.empty[NegotiationError]

    val edgeOfSource = settled.edges.map(e => e.bind.source -> e).toMap
    val edgeOfTarget = settled.edges.map(e => e.bind.target -> e).toMap
    val groupOfBind  = settled.dvGroups.flatMap(g => g.binds.map(b => b -> g)).toMap

    val resolved = spec.generatorModules.flatMap { g =>
      var failed    = false
      val nodeViews = g.nodes.map { n =>
        val id   = ModuleNodeId(g.id, n.name)
        val edge = n.direction match
          case NodeDirection.Outward => edgeOfSource(id)
          case NodeDirection.Inward  => edgeOfTarget(id)
        val refs = n.refs.flatMap { r =>
          val targetSpec = if r.target.module == g.id then g.node(r.target.name) else None
          targetSpec match
            case None     =>
              errors += NegotiationError.ReferenceFailed(
                s"reference target does not exist in ${g.id.show}",
                id,
                r.target,
                r.expectedProtocol,
                Vector(r.loc)
              )
              failed = true
              None
            case Some(ts) =>
              if ts.protocol.id != r.expectedProtocol then
                errors += NegotiationError.ReferenceFailed(
                  s"reference target protocol is ${ts.protocol.id.show}",
                  id,
                  r.target,
                  r.expectedProtocol,
                  Vector(r.loc)
                )
                failed = true
                None
              else
                val targetEdge = ts.direction match
                  case NodeDirection.Outward => edgeOfSource(r.target)
                  case NodeDirection.Inward  => edgeOfTarget(r.target)
                Some(ResolvedProtocolReference(r.refName, id, r.target, ts.protocol, targetEdge.edge))
        }
        NodeView(id, n.direction, edge, refs)
      }

      val sourceViews = g.dvSources.map { s =>
        val id    = DVSourceId(g.id, s.name)
        val bind  = spec.dvBinds.find(_.source == id).get.bindId
        val group = groupOfBind(bind)
        val index = group.binds.indexOf(bind)
        SourceView(id, bind, s.protocol, group.edge, group.interfaces.sources(index), s.layer)
      }
      val sinkViews   = g.dvSinks.map { s =>
        val id    = DVSinkId(g.id, s.name)
        val group = settled.dvGroups.find(_.sink == id).get
        SinkView(id, group.binds, s.protocol, group.edge, group.interfaces)
      }

      if failed then None
      else
        val view = EdgeView(g.id, nodeViews, VerificationView(sourceViews, sinkViews))
        g.computeProtocolParam(view) match
          case Left(violation) =>
            errors += NegotiationError.CapabilityExceeded(g.id, violation, Vector(g.loc))
            None
          case Right(pp)       =>
            val fp      = g.combine(pp)
            val encoded = g.entry.fullParamCodec.asInstanceOf[Codec[Any]].encode(fp)
            Some(ResolvedGeneratorModule(g.id, g.entry, view, pp, fp, encoded))
    }

    if errors.nonEmpty then Left(NegotiationError.normalize(errors.toVector))
    else Right(resolved)
