package me.jiuyang.syntheke

import scala.collection.immutable.SortedSet

/** Turns a frozen `DesignSpec` into a `ResolvedDesign`: structure, domains, Down/Up propagation, per-edge
  * negotiation, then each generator module's full parameter and probes. A check reports every failure it finds; a
  * computation that later values depend on stops at its first failure.
  */
object Negotiator:
  def negotiate[A](design: Design[A]): ResolvedDesign = design.frozen._1

  private[syntheke] def build[A](design: Design[A]): (ResolvedDesign, A) =
    val session       = new BuildSession(ModuleId.root)
    val (spec, ports) = session.build(design.moduleName, design.loc)(design.body)
    (session.resolve(spec), ports)

  private[syntheke] def resolve(spec: DesignSpec, imported: Option[ProbeCatalog]): Negotiated =
    report("structure", structure(spec))
    val domains                            = Settlement(spec)
    val (down, up)                         = propagate(spec, parameterOrder(spec))
    val edges                              = settle(spec, down, up)
    val (generators, probes, observations) = assemble(spec, domains, edges, imported)
    Negotiated(spec, domains, edges, generators, probes, observations)

  private def at(locs: SourceLoc*): String = locs.map(_.show).mkString(", ")

  private def structure(spec: DesignSpec): Vector[String] =
    val generators = spec.generators.groupBy(_.name).toVector.sortBy(_._1).collect {
      case (name, definitions) if definitions.sizeIs > 1 =>
        s"generator name '$name' is used by ${definitions.size} distinct definitions"
    }
    val wrappers   = spec.moduleOrder.flatMap(spec.wrapper).groupBy(_.moduleName).toVector.sortBy(_._1).collect {
      case (name, ws) if ws.sizeIs > 1 =>
        s"wrapper module name '$name' is declared by ${ws.map(_.id.show).mkString(", ")}, at ${at(ws.map(_.loc)*)}"
    }
    val binds      = spec.binds.flatMap { b =>
      (spec.nodeSpec(b.source), spec.nodeSpec(b.target)) match
        case (None, _)                    => Vector(s"bind source ${b.source.show} is not a node of this design, at ${at(b.loc)}")
        case (_, None)                    => Vector(s"bind target ${b.target.show} is not a node of this design, at ${at(b.loc)}")
        case (Some(source), Some(target)) =>
          Option.when(!(source.protocol eq target.protocol))(
            s"${b.id.show} joins different protocols, at ${at(b.loc, source.loc, target.loc)}"
          ).toVector ++ Option.when(!(b.declaredIn.isAncestorOf(b.source.module) && b.declaredIn.isAncestorOf(b.target.module)))(
            s"${b.id.show} is declared in ${b.declaredIn.show}, which is not an ancestor of both ends, at ${at(b.loc)}"
          )
    }
    val asSource   = spec.binds.groupBy(_.source)
    val asTarget   = spec.binds.groupBy(_.target)
    val once       = for
      m <- spec.nodeModules
      n <- m.nodes
      id     = ModuleNodeId(m.id, n.name)
      joined = n.direction match
        case NodeDirection.Outward => asSource.getOrElse(id, Vector.empty)
        case NodeDirection.Inward  => asTarget.getOrElse(id, Vector.empty)
      if joined.size != 1
    yield s"${n.direction.toString.toLowerCase} node ${id.show} is in ${joined.size} binds, not exactly 1, at ${
        at((joined.map(_.loc) :+ n.loc)*)
      }"
    generators ++ wrappers ++ binds ++ once

  /** Kahn's algorithm; on a cycle, the nodes on it. */
  private def topologicalOrder[A: Ordering](nodes: Vector[A], successors: Map[A, Vector[A]]): Either[Vector[A], Vector[A]] =
    val indegree = successors.values.flatten.groupMapReduce(identity)(_ => 1)(_ + _).withDefaultValue(0)
    @annotation.tailrec
    def kahn(ready: SortedSet[A], degrees: Map[A, Int], result: Vector[A]): Vector[A] =
      ready.headOption match
        case None       => result
        case Some(head) =>
          val (next, opened) = successors(head).foldLeft(degrees -> Vector.empty[A]) { case ((current, opened), s) =>
            val degree = current(s) - 1
            current.updated(s, degree) -> (if degree == 0 then opened :+ s else opened)
          }
          kahn(ready - head ++ opened, next, result :+ head)
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

  /** Binds lead from source to target; inside a module an outward node follows the inward nodes it reads, and an
    * inward node precedes the outward nodes it reads.
    */
  private def parameterOrder(spec: DesignSpec): Vector[ModuleNodeId] =
    val preorder = spec.moduleOrder.zipWithIndex.toMap
    val nodes    = for m <- spec.nodeModules; n <- m.nodes yield ModuleNodeId(m.id, n.name) -> n
    val key      = spec.nodeModules.flatMap(m => m.nodes.zipWithIndex.map((n, i) => ModuleNodeId(m.id, n.name) -> (preorder(m.id), i))).toMap
    val reads    = for
      (id, n) <- nodes
      read    <- n.computation.reads
    yield if n.direction == NodeDirection.Outward then read -> id else id -> read
    val edges    = spec.binds.map(b => b.source -> b.target) ++ reads
    given Ordering[ModuleNodeId] = Ordering.by(key)
    topologicalOrder(nodes.map(_._1), edges.groupMap(_._1)(_._2).withDefaultValue(Vector.empty)) match
      case Right(sorted) => sorted
      case Left(cycle)   =>
        fail(s"parameter dependency graph has a cycle through ${cycle.map(_.show).mkString(", ")}", cycle.flatMap(spec.nodeSpec(_).map(_.loc))*)

  private def propagate(spec: DesignSpec, order: Vector[ModuleNodeId]): (Map[ModuleNodeId, Any], Map[ModuleNodeId, Any]) =
    val sourceOf = spec.binds.map(b => b.target -> b.source).toMap
    val targetOf = spec.binds.map(b => b.source -> b.target).toMap

    def show(id: ModuleNodeId, value: Any): String =
      val node = spec.nodeSpec(id).get
      val writer = if node.direction == NodeDirection.Inward then node.protocol.downWriter else node.protocol.upWriter
      s"${id.show}=${ujson.write(serialize(writer, value))}"

    def evaluate(values: Map[ModuleNodeId, Any], id: ModuleNodeId): Any =
      val node   = spec.nodeSpec(id).get
      val inputs = node.computation.reads.map(read => read -> values(read)).toMap
      node.computation match
        case NodeComputation.Constant(value)     => value
        case NodeComputation.Derived(_, compute) =>
          compute(inputs).fold(
            v => fail(s"propagation failed at ${id.show} (${node.direction}): ${v.message}; inputs [${
                inputs.map(show).mkString(", ")
              }]", node.loc),
            identity
          )

    def pass(order: Vector[ModuleNodeId], computed: NodeDirection, across: Map[ModuleNodeId, ModuleNodeId]) =
      order.foldLeft(Map.empty[ModuleNodeId, Any]) { (values, id) =>
        values.updated(id, if spec.nodeSpec(id).get.direction == computed then evaluate(values, id) else values(across(id)))
      }
    (pass(order, NodeDirection.Outward, sourceOf), pass(order.reverse, NodeDirection.Inward, targetOf))

  private def settle(spec: DesignSpec, down: Map[ModuleNodeId, Any], up: Map[ModuleNodeId, Any]): Vector[ResolvedEdge] =
    spec.binds.map { bind =>
      val protocol = spec.nodeSpec(bind.source).get.protocol.asInstanceOf[Protocol { type Down = Any; type Up = Any; type Edge = Any }]
      val edge     = protocol.negotiate(down(bind.source), up(bind.target))
        .fold(v => fail(s"negotiation failed at ${bind.id.show}: ${v.message}", bind.loc), identity)
      val bundle   = protocol.interface(edge)
      check(!ProtocolInterface.containsProbe(bundle), bind.loc)(
        s"the interface of ${bind.id.show} contains a probe; probes are read by observers"
      )
      ResolvedEdge(bind.id, protocol, down(bind.source), up(bind.target), edge, bundle)
    }

  private def assemble(spec: DesignSpec, domains: DomainGraph, edges: Vector[ResolvedEdge], imported: Option[ProbeCatalog])
    : (Vector[ResolvedGeneratorModule], ProbeCatalog, Map[ModuleId, ProbeBindings]) =
    val edgeOf = edges.flatMap(e => Vector(e.bind.source -> e, e.bind.target -> e)).toMap

    val generators = spec.generatorModules.map { g =>
      val view       = EdgeView(g.id, g.nodes.map(n => NodeView(ModuleNodeId(g.id, n.name), n.direction, edgeOf(ModuleNodeId(g.id, n.name)))))
      val fullParam  = g.parameters(view, domains).fold(v => fail(s"${g.id.show} cannot take its parameters: ${v.message}", g.loc), identity)
      val definition = g.definition.asInstanceOf[GeneratorDefinition[Any]]
      ResolvedGeneratorModule(g.id, definition, view, fullParam, definition.probes(fullParam))
    }
    report(
      "public probes",
      generators.flatMap { g =>
        val loc   = spec.generatorModule(g.module).get.loc
        val names = g.probeDeclaration.ports.map(_.name)
        names.diff(names.distinct).distinct.map(name => s"${g.module.show} declares public probe '$name' twice, at ${at(loc)}") ++
          names.filter(name => g.view.nodes.exists(_.node.name == name))
            .map(name => s"public probe ${g.module.show}#$name has the name of a node, at ${at(loc)}")
      }
    )

    val local   = new ProbeCatalog(
      generators.flatMap { generator =>
        spec.generatorModule(generator.module).get.probes.flatMap { declared =>
          declared.selector.asInstanceOf[ProbeSelector[Any, Any]].resolve(generator.fullParam, generator.probeDeclaration) match
            case Left(violation)       => fail(s"probe ${declared.node.id.show} selects nothing: ${violation.message}", declared.loc)
            case Right(None)           => Vector.empty
            case Right(Some(selected)) =>
              val portId = ModuleNodeId(generator.module, selected.portName)
              val port   = generator.probeDeclaration.ports.find(_.name == selected.portName)
                .map(public => ResolvedPublicPort(portId, public.tpe))
                .getOrElse(fail(s"probe ${declared.node.id.show} selects ${portId.show}, which is not public", declared.loc))
              Vector(new ResolvedProbe(declared.node.asInstanceOf[ProbeNode[Any]], selected.parameters, port, selected.implementation))
        }
      }
    )
    val catalog = imported.fold(local)(_.combined(local))

    val observations = generators.flatMap { module =>
      val inputs = module.definition.asInstanceOf[GeneratorDefinition[Any]].observations(module.fullParam)
      Option.when(inputs.ports.nonEmpty)(module -> inputs)
    }
    report(
      "observations",
      observations.flatMap { (module, inputs) =>
        val loc   = spec.generatorModule(module.module).get.loc
        val taken = module.view.nodes.map(_.node.name).toSet ++ module.probeDeclaration.ports.map(_.name)
        catalog.foreign(inputs).map(p => s"${module.module.show} observes ${p.id.show}, which is not in its design's catalog, at ${at(loc)}") ++
          inputs.ports.filter(p => taken(p.portName))
            .map(p => s"${module.module.show} observes into '${p.portName}', the name of one of its ports, at ${at(loc)}")
      }
    )
    (generators, catalog, observations.map((module, inputs) => module.module -> inputs).toMap)
