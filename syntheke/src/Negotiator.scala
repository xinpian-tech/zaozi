package me.jiuyang.syntheke

import scala.collection.immutable.SortedSet

final class NegotiationException(message: String) extends RuntimeException(message)

/** Turns a frozen `DesignSpec` into a `ResolvedDesign`: structure, domains, Down/Up propagation, per-edge
  * negotiation, then each generator module's full parameter and probes. Computation stops at the first error.
  */
object Negotiator:
  def negotiate[A](design: Design[A]): ResolvedDesign = design.frozen._1

  private[syntheke] def build[A](design: Design[A]): (ResolvedDesign, A) =
    val session       = new BuildSession(ModuleId.root)
    val (spec, ports) = session.build(design.moduleName, design.loc)(design.body)
    (session.resolve(spec), ports)

  private[syntheke] def resolve(spec: DesignSpec, imported: Option[ProbeCatalog]): ResolvedDesign =
    structuralCheck(spec)
    val domains                            = Settlement(spec)
    val (down, up)                         = propagate(spec, parameterTopology(spec))
    val edges                              = settle(spec, down, up)
    val (generators, probes, observations) = assembleViews(spec, domains, edges, imported)
    ResolvedDesign(
      spec = spec,
      domains = domains,
      edges = edges,
      generatorModules = generators,
      portPlans = Vector.empty,
      wirePlans = Vector.empty,
      layerDecls = Map.empty,
      probes = probes,
      observations = observations,
      dependencies = Vector.empty
    )

  private def fail(message: String): Nothing = throw NegotiationException(message)

  private def at(locs: (sourcecode.File, sourcecode.Line)*): String = locs.map(_.show).mkString(", ")

  private def structuralCheck(spec: DesignSpec): Unit =
    spec.generators.groupBy(_.name).collectFirst { case (name, ds) if ds.sizeIs > 1 => name }.foreach { name =>
      fail(s"generator name '$name' used by ${spec.generators.count(_.name == name)} distinct definitions")
    }

    spec.moduleOrder.flatMap(spec.wrapper).foldLeft(Map.empty[String, ModuleId]) { (seen, w) =>
      seen.get(w.moduleName) match
        case Some(first) =>
          fail(s"wrapper module name '${w.moduleName}' declared by both ${first.show} and ${w.id.show}, at ${at(w.loc)}")
        case None        => seen + (w.moduleName -> w.id)
    }

    spec.binds.foreach { b =>
      val source = spec
        .nodeSpec(b.source)
        .getOrElse(fail(s"bind source ${b.source.show} is not a node of this design, at ${at(b.loc)}"))
      val target = spec
        .nodeSpec(b.target)
        .getOrElse(fail(s"bind target ${b.target.show} is not a node of this design, at ${at(b.loc)}"))
      if !(source.protocol eq target.protocol) then
        fail(s"bind ${b.bindId.show} uses different protocol objects at its endpoints, at ${at(b.loc, source.loc, target.loc)}")
      if !(b.declaredIn.isAncestorOf(b.source.module) && b.declaredIn.isAncestorOf(b.target.module)) then
        fail(
          s"bind ${b.source.show} -> ${b.target.show} declared in ${b.declaredIn.show}, " +
            s"which is not an ancestor of both endpoints, at ${at(b.loc)}"
        )
    }

    val asSource = spec.binds.groupBy(_.source)
    val asTarget = spec.binds.groupBy(_.target)
    for
      m <- spec.nodeModules
      n <- m.nodes
    do
      val id            = ModuleNodeId(m.id, n.name)
      val (role, binds) = n.direction match
        case NodeDirection.Outward => "source" -> asSource.getOrElse(id, Vector.empty)
        case NodeDirection.Inward  => "target" -> asTarget.getOrElse(id, Vector.empty)
      if binds.size != 1 then
        fail(
          s"${n.direction.toString.toLowerCase} node ${id.show} is the $role of ${binds.size} binds, " +
            s"expected exactly 1, at ${at((binds.map(_.loc) :+ n.loc)*)}"
        )

  private def topologicalOrder[A: Ordering](
    nodes:      Vector[A],
    successors: Map[A, Vector[A]],
    indegree:   Map[A, Int]
  ): Either[Vector[A], Vector[A]] =
    @annotation.tailrec
    def kahn(ready: SortedSet[A], degrees: Map[A, Int], result: Vector[A]): Vector[A] =
      ready.headOption match
        case None       => result
        case Some(head) =>
          val (nextDegrees, newlyReady) = successors(head).foldLeft(degrees -> Vector.empty[A]) {
            case ((current, opened), successor) =>
              val degree = current(successor) - 1
              current.updated(successor, degree) -> (if degree == 0 then opened :+ successor else opened)
          }
          kahn(ready - head ++ newlyReady, nextDegrees, result :+ head)

    val sorted = kahn(SortedSet.from(nodes.filter(indegree(_) == 0)), indegree, Vector.empty)
    if sorted.size == nodes.size then Right(sorted)
    else
      val remainder = nodes.toSet -- sorted
      def cyclic(id: A): Boolean =
        @annotation.tailrec
        def visit(frontier: Vector[A], seen: Set[A]): Boolean = frontier match
          case head +: tail =>
            if head == id then true
            else if seen(head) then visit(tail, seen)
            else visit(tail ++ successors(head).filter(remainder), seen + head)
          case _            => false
        visit(successors(id).filter(remainder), Set.empty)
      Left(remainder.toVector.filter(cyclic).sorted)

  private def parameterTopology(spec: DesignSpec): Vector[ModuleNodeId] =
    val preorder                 = spec.moduleOrder.zipWithIndex.toMap
    val nodeKey                  = spec.nodeModules
      .flatMap(m => m.nodes.map(n => ModuleNodeId(m.id, n.name) -> (preorder(m.id), n.order)))
      .toMap
    val nodeIds                  = nodeKey.keys.toVector
    val edges                    = spec.binds.map(b => b.source -> b.target) ++ (
      for
        m <- spec.nodeModules
        d <- m.dependencies
      yield ModuleNodeId(m.id, d.from) -> ModuleNodeId(m.id, d.to)
    )
    val successors               = edges.groupMap(_._1)(_._2).withDefaultValue(Vector.empty)
    val predecessors             = edges.groupMap(_._2)(_._1).withDefaultValue(Vector.empty)
    val indegree                 = nodeIds.map(id => id -> predecessors(id).size).toMap
    given Ordering[ModuleNodeId] = Ordering.by(nodeKey)

    topologicalOrder(nodeIds, successors, indegree) match
      case Right(sorted) => sorted
      case Left(members) =>
        val onCycle = members.toSet
        val locs    = members.flatMap(id => spec.nodeSpec(id).map(_.loc)) ++
          spec.binds.filter(b => onCycle(b.source) && onCycle(b.target)).map(_.loc) ++
          spec.nodeModules.flatMap(m =>
            m.dependencies.filter(d => onCycle(ModuleNodeId(m.id, d.from)) && onCycle(ModuleNodeId(m.id, d.to))).map(_.loc)
          )
        fail(s"parameter dependency graph has a cycle through ${members.map(_.show).mkString(", ")}, at ${at(locs*)}")

  private def propagate(
    spec:  DesignSpec,
    order: Vector[ModuleNodeId]
  ): (Map[ModuleNodeId, Any], Map[ModuleNodeId, Any]) =
    val bindOfSource = spec.binds.map(b => b.source -> b).toMap
    val bindOfTarget = spec.binds.map(b => b.target -> b).toMap

    def specOf(id: ModuleNodeId): NodeSpec = spec.nodeSpec(id).get

    def show(token: ReadToken, value: Any): String = token match
      case r: DownReader[?] => s"${r.node.show}=${ujson.write(write(specOf(r.node).protocol.downWriter, value))}"
      case r: UpReader[?]   => s"${r.node.show}=${ujson.write(write(specOf(r.node).protocol.upWriter, value))}"

    def evaluate(values: Map[ModuleNodeId, Any], id: ModuleNodeId): Any =
      val node   = specOf(id)
      val reads  = node.computation.readPlan.tokens
      val inputs = reads.map { case r: Reader[?] =>
        r -> values.getOrElse(r.node, fail(s"${id.show} reads ${r.node.show} before it is available"))
      }.toMap[ReadToken, Any]
      node.computation(new ReadValues(inputs)) match
        case Right(value) => value
        case Left(v)      =>
          fail(
            s"propagation failed at ${id.show} (${node.direction}): ${v.message}; " +
              s"inputs [${reads.map(t => show(t, inputs(t))).mkString(", ")}], at ${at(node.loc)}"
          )

    val down = order.foldLeft(Map.empty[ModuleNodeId, Any]) { (values, id) =>
      values.updated(
        id,
        specOf(id).direction match
          case NodeDirection.Outward => evaluate(values, id)
          case NodeDirection.Inward  => values(bindOfTarget(id).source)
      )
    }
    val up   = order.reverse.foldLeft(Map.empty[ModuleNodeId, Any]) { (values, id) =>
      values.updated(
        id,
        specOf(id).direction match
          case NodeDirection.Inward  => evaluate(values, id)
          case NodeDirection.Outward => values(bindOfSource(id).target)
      )
    }
    (down, up)

  private def write(writer: upickle.default.Writer[?], value: Any): ujson.Value =
    upickle.default.writeJs(value)(using writer.asInstanceOf[upickle.default.Writer[Any]])

  private def settle(spec: DesignSpec, down: Map[ModuleNodeId, Any], up: Map[ModuleNodeId, Any]): Vector[ResolvedEdge] =
    spec.binds.map { bind =>
      val protocol = spec.nodeSpec(bind.source).get.protocol.asInstanceOf[Protocol { type Down = Any; type Up = Any; type Edge = Any }]
      val edge     = protocol.negotiate(down(bind.source), up(bind.target)) match
        case Left(v)     => fail(s"settle failed at ${bind.bindId.show}: ${v.message}, at ${at(bind.loc)}")
        case Right(edge) => edge
      val bundle   = protocol.interface(edge)
      ProtocolInterface.leaves(bundle).collectFirst { case (path, _: ProtocolInterface.Probe) => path }.foreach { path =>
        fail(
          s"design interface of ${bind.bindId.show} contains a Probe at ${path.show}; " +
            s"probes belong to verification protocols, at ${at(bind.loc)}"
        )
      }
      ResolvedEdge(bind.bindId, protocol, down(bind.source), up(bind.target), edge, bundle)
    }

  private def assembleViews(
    spec:     DesignSpec,
    domains:  DomainGraph,
    edges:    Vector[ResolvedEdge],
    imported: Option[ProbeCatalog]
  ): (Vector[ResolvedGeneratorModule], ProbeCatalog, Map[ModuleId, ProbeBindings]) =
    val edgeOf = edges.flatMap(e => Vector(e.bind.source -> e, e.bind.target -> e)).toMap

    val generators = spec.generatorModules.map { g =>
      val view       = EdgeView(g.id, g.nodes.map(n => NodeView(ModuleNodeId(g.id, n.name), n.direction, edgeOf(ModuleNodeId(g.id, n.name)))))
      val fullParam  = g.parameters(view, domains) match
        case Left(v)      => fail(s"capability exceeded at ${g.id.show}: ${v.message}, at ${at(g.loc)}")
        case Right(value) => value
      val definition = g.definition.asInstanceOf[GeneratorDefinition[Any]]
      val probes     = definition.probes(fullParam)
      if probes.ports.map(_.name).distinct.size != probes.ports.size then
        fail(s"generator ${g.id.show} has duplicate public Probe names, at ${at(g.loc)}")
      probes.ports.find(port => g.nodes.exists(_.name == port.name)).foreach { port =>
        fail(s"public Probe ${g.id.show}#${port.name} conflicts with a functional node, at ${at(g.loc)}")
      }
      ResolvedGeneratorModule(g.id, definition, view, fullParam, probes)
    }

    val localCatalog = ProbeCatalog.resolve(generators.map(g => g.module -> g.probeDeclaration)) { ports =>
      generators.flatMap { generator =>
        spec.generatorModule(generator.module).get.probes.flatMap { declared =>
          declared.resolve(generator.fullParam, generator.probeDeclaration) match
            case Left(violation)       =>
              fail(s"Probe selector failed at ${declared.node.id.show}: ${violation.message}, at ${at(declared.loc)}")
            case Right(None)           => Vector.empty
            case Right(Some(selected)) =>
              val portId = ModuleNodeId(generator.module, selected.portName)
              val port   = ports
                .find(_.id == portId)
                .getOrElse(fail(s"Probe ${declared.node.id.show} selects missing public port ${portId.show}, at ${at(declared.loc)}"))
              Vector(new ResolvedProbe(declared.node, selected.parameters, port, selected.implementation))
        }
      }
    }
    val catalog      = imported.fold(localCatalog)(_.combined(localCatalog))
    val observations = generators.flatMap { module =>
      val inputs = module.definition.asInstanceOf[GeneratorDefinition[Any]].observations(module.fullParam)
      catalog.validate(inputs)
      val names  = spec.generatorModule(module.module).get.nodes.map(_.name).toSet ++
        module.probeDeclaration.ports.map(_.name)
      inputs.ports.find(input => names(input.portName)).foreach { input =>
        fail(s"Probe input '${input.portName}' conflicts with a declared port in ${module.module.show}")
      }
      Option.when(inputs.ports.nonEmpty)(module.module -> inputs)
    }.toMap
    (generators, catalog, observations)
