package me.jiuyang.syntheke

import scala.collection.immutable.VectorMap

/** Turns a frozen `DesignSpec` into a `ResolvedDesign`: structure, domains, Down/Up propagation, per-edge negotiation,
  * each generator module's full parameter and probes, the frozen contracts of the instances, and the plan of ports and
  * wires. A check reports every failure it finds; a computation that later values depend on stops at its first failure.
  */
object Negotiator:
  def negotiate[A](design: Design[A]): ResolvedDesign = design.frozen._1

  private[syntheke] def build[A](design: Design[A]): (ResolvedDesign, A) =
    val session       = new BuildSession(ModuleId.root)
    val (spec, ports) = session.build(design.moduleName, design.loc)(design.body)
    report(
      "design boundaries",
      design.dangles
        .boundaries(ports)
        .filterNot(b => spec.boundaries.exists(_ eq b))
        .map(b =>
          s"design ${design.moduleName} hands out ${b.id.show}, which is not one of its boundaries; forward it with " +
            s"`.boundary`, at ${at(design.loc)}"
        )
    )
    (session.resolve(spec), ports)

  /** `forwarded` are the probes of instances this design makes public. */
  private[syntheke] def resolve(spec: DesignSpec, imports: Vector[BoundaryImport], forwarded: Vector[ResolvedProbe[?]])
    : ResolvedDesign =
    report("structure", structure(spec))
    DomainChecks(spec, imports.flatMap(_.copiedDomains))
    val (down, up)                         = propagate(spec, parameterOrder(spec))
    val edges                              = settle(spec, down, up)
    val instanceProbes                     = imports.map(_.probes).reduceOption(_.combined(_))
    val (generators, probes, observations) = assemble(spec, edges, instanceProbes)
    report("integration", imports.flatMap(_.violations(edges)))
    val published                          = probes.published(spec.generatorModules.flatMap(_.probes.map(_.node)), forwarded)
    val (ports, wires, layers)             = Planner.plan(spec, edges, published, observations, imports.map(i => i.root -> i.loc))
    ResolvedDesign(
      spec,
      edges,
      generators,
      ports,
      wires,
      layers,
      published,
      observations,
      imports.map(i => i.root -> i.definition)
    )

  private def at(locs: SourceLoc*): String = locs.map(_.show).mkString(", ")

  private def structure(spec: DesignSpec): Vector[String] =
    val generators = spec.generators.groupedBy(_.name).collect {
      case (name, definitions) if definitions.sizeIs > 1 =>
        s"generator name '$name' is used by ${definitions.size} distinct definitions"
    }
    val wrappers   = spec.moduleOrder.flatMap(spec.wrapper).groupedBy(_.moduleName).collect {
      case (name, ws) if ws.sizeIs > 1 =>
        s"wrapper module name '$name' is declared by ${ws.map(_.id.show).mkString(", ")}, at ${at(ws.map(_.loc)*)}"
    }
    val binds      = spec.binds.flatMap { b =>
      (spec.nodeSpec(b.source), spec.nodeSpec(b.target)) match
        case (None, _)                    => Vector(s"bind source ${b.source.show} is not a node of this design, at ${at(b.loc)}")
        case (_, None)                    => Vector(s"bind target ${b.target.show} is not a node of this design, at ${at(b.loc)}")
        case (Some(source), Some(target)) =>
          Option
            .when(!(source.protocol eq target.protocol))(
              s"${b.id.show} joins different protocols, at ${at(b.loc, source.loc, target.loc)}"
            )
            .toVector ++ Option.when(
            !(b.declaredIn.isAncestorOf(b.source.module) && b.declaredIn.isAncestorOf(b.target.module))
          )(
            s"${b.id.show} is declared in ${b.declaredIn.show}, which is not an ancestor of both ends, at ${at(b.loc)}"
          )
    }
    val asSource   = VectorMap.from(spec.binds.groupedBy(_.source))
    val asTarget   = VectorMap.from(spec.binds.groupedBy(_.target))
    val once       = for
      m     <- spec.nodeModules
      n     <- m.nodes
      id     = ModuleNodeId(m.id, n.name)
      joined = n.direction match
                 case NodeDirection.Outward => asSource.getOrElse(id, Vector.empty)
                 case NodeDirection.Inward  => asTarget.getOrElse(id, Vector.empty)
      if joined.size != 1
    yield s"${n.direction.toString.toLowerCase} node ${id.show} is in ${joined.size} binds, not exactly 1, at ${at((joined.map(_.loc) :+ n.loc)*)}"
    generators ++ wrappers ++ binds ++ once

  /** Kahn's algorithm, taking the least ready node first; on a cycle, the nodes on it in order. */
  private def topologicalOrder[A: Ordering](nodes: Vector[A], successors: A => Vector[A])
    : Either[Vector[A], Vector[A]] =
    val indegree =
      VectorMap.from(nodes.map(_ -> 0)) ++ nodes.flatMap(successors).groupedBy(identity).map((n, in) => n -> in.size)
    @annotation.tailrec
    def kahn(ready: Vector[A], degrees: VectorMap[A, Int], result: Vector[A]): Vector[A] = ready match
      case head +: rest =>
        val (next, opened) = successors(head).foldLeft(degrees -> Vector.empty[A]) { case ((current, opened), s) =>
          val degree = current(s) - 1
          current.updated(s, degree) -> (if degree == 0 then opened :+ s else opened)
        }
        kahn((rest ++ opened).sorted, next, result :+ head)
      case _            => result
    val sorted   = kahn(nodes.filter(indegree(_) == 0).sorted, indegree, Vector.empty)
    if sorted.size == nodes.size then Right(sorted)
    else
      val remainder = nodes.filterNot(sorted.contains)
      def cyclic(id: A): Boolean =
        @annotation.tailrec
        def visit(frontier: Vector[A], seen: Vector[A]): Boolean = frontier match
          case head +: tail =>
            if head == id then true
            else if seen.contains(head) then visit(tail, seen)
            else visit(tail ++ successors(head).filter(remainder.contains), seen :+ head)
          case _            => false
        visit(successors(id).filter(remainder.contains), Vector.empty)
      Left(remainder.filter(cyclic).sorted)

  /** Binds lead from source to target; inside a module an outward node follows the inward nodes it reads, and an inward
    * node precedes the outward nodes it reads.
    */
  private def parameterOrder(spec: DesignSpec): Vector[ModuleNodeId] =
    val preorder                 = VectorMap.from(spec.moduleOrder.zipWithIndex)
    val nodes                    = for m <- spec.nodeModules; n <- m.nodes yield ModuleNodeId(m.id, n.name) -> n
    val key                      =
      VectorMap.from(
        spec.nodeModules.flatMap(m =>
          m.nodes.zipWithIndex.map((n, i) => ModuleNodeId(m.id, n.name) -> (preorder(m.id), i))
        )
      )
    val reads                    = for
      (id, n) <- nodes
      read    <- n.computation.reads
    yield if n.direction == NodeDirection.Outward then read -> id else id -> read
    val edges                    = spec.binds.map(b => b.source -> b.target) ++ reads
    given Ordering[ModuleNodeId] = Ordering.by(key)
    val successors               = VectorMap.from(edges.groupedBy(_._1).map((from, out) => from -> out.map(_._2)))
    topologicalOrder(nodes.map(_._1), successors.getOrElse(_, Vector.empty)) match
      case Right(sorted) => sorted
      case Left(cycle)   =>
        fail(
          s"parameter dependency graph has a cycle through ${cycle.map(_.show).mkString(", ")}",
          cycle.flatMap(spec.nodeSpec(_).map(_.loc))*
        )

  private def propagate(spec: DesignSpec, order: Vector[ModuleNodeId])
    : (VectorMap[ModuleNodeId, Any], VectorMap[ModuleNodeId, Any]) =
    val sourceOf = VectorMap.from(spec.binds.map(b => b.target -> b.source))
    val targetOf = VectorMap.from(spec.binds.map(b => b.source -> b.target))

    def show(id: ModuleNodeId, value: Any): String =
      val node   = spec.nodeSpec(id).get
      val writer = if node.direction == NodeDirection.Inward then node.protocol.downWriter else node.protocol.upWriter
      s"${id.show}=${ujson.write(serialize(writer, value))}"

    def evaluate(values: VectorMap[ModuleNodeId, Any], id: ModuleNodeId): Any =
      val node   = spec.nodeSpec(id).get
      val inputs = node.computation.reads.map(read => read -> values(read))
      node.computation
        .compute(VectorMap.from(inputs))
        .fold(
          v =>
            fail(
              s"propagation failed at ${id.show} (${node.direction}): ${v.message}; inputs [${inputs.map(show).mkString(", ")}]",
              node.loc
            ),
          identity
        )

    def pass(order: Vector[ModuleNodeId], computed: NodeDirection, across: VectorMap[ModuleNodeId, ModuleNodeId]) =
      order.foldLeft(VectorMap.empty[ModuleNodeId, Any]) { (values, id) =>
        values.updated(
          id,
          if spec.nodeSpec(id).get.direction == computed then evaluate(values, id) else values(across(id))
        )
      }
    (pass(order, NodeDirection.Outward, sourceOf), pass(order.reverse, NodeDirection.Inward, targetOf))

  private def settle(spec: DesignSpec, down: VectorMap[ModuleNodeId, Any], up: VectorMap[ModuleNodeId, Any])
    : Vector[ResolvedEdge] =
    val edges = spec.binds.map { bind =>
      val protocol = spec
        .nodeSpec(bind.source)
        .get
        .protocol
        .asInstanceOf[Protocol { type Down = Any; type Up = Any; type Edge = Any }]
      val edge     = protocol
        .negotiate(down(bind.source), up(bind.target))
        .fold(v => fail(s"negotiation failed at ${bind.id.show}: ${v.message}", bind.loc), identity)
      ResolvedEdge(bind.id, protocol, down(bind.source), up(bind.target), edge, protocol.interface(edge))
    }
    report(
      "interfaces",
      edges.zip(spec.binds).collect {
        case (edge, bind) if ProtocolInterface.containsProbe(edge.interface) =>
          s"the interface of ${bind.id.show} contains a probe; probes are read by observers, at ${at(bind.loc)}"
      }
    )
    edges

  private def assemble(spec: DesignSpec, edges: Vector[ResolvedEdge], imported: Option[ProbeCatalog])
    : (Vector[ResolvedGeneratorModule], ProbeCatalog, VectorMap[ModuleId, ProbeBindings]) =
    val edgeOf = VectorMap.from(edges.flatMap(e => Vector(e.bind.source -> e, e.bind.target -> e)))

    val generators = spec.generatorModules.map { g =>
      val view       =
        EdgeView(
          g.id,
          g.nodes.map(n => NodeView(ModuleNodeId(g.id, n.name), edgeOf(ModuleNodeId(g.id, n.name)))),
          g.loc
        )
      val fullParam  =
        g.parameters(view).fold(v => fail(s"${g.id.show} cannot take its parameters: ${v.message}", g.loc), identity)
      val definition = g.definition.asInstanceOf[GeneratorDefinition[Any]]
      ResolvedGeneratorModule(g.id, view, fullParam, definition.probes(fullParam))
    }
    report(
      "public probes",
      generators.flatMap { g =>
        val loc   = spec.generatorModule(g.module).get.loc
        val names = g.probeDeclaration.ports.map(_.name)
        names
          .diff(names.distinct)
          .distinct
          .map(name => s"${g.module.show} declares public probe '$name' twice, at ${at(loc)}") ++
          names
            .filterNot(DeclaredName.legal)
            .map(name => s"public probe ${g.module.show}#$name is not a legal name, at ${at(loc)}") ++
          names
            .filter(name => g.view.nodes.exists(_.node.name == name))
            .map(name => s"public probe ${g.module.show}#$name has the name of a node, at ${at(loc)}")
      }
    )

    val local   = new ProbeCatalog(
      generators.flatMap { generator =>
        spec.generatorModule(generator.module).get.probes.flatMap { declared =>
          declared.selector
            .asInstanceOf[ProbeSelector[Any, Any]]
            .resolve(generator.fullParam, generator.probeDeclaration) match
            case Left(violation)       =>
              fail(s"probe ${declared.node.id.show} selects nothing: ${violation.message}", declared.loc)
            case Right(None)           => Vector.empty
            case Right(Some(selected)) =>
              val portId = ModuleNodeId(generator.module, selected.portName)
              val port   = generator.probeDeclaration.ports
                .find(_.name == selected.portName)
                .map(public => ResolvedPublicPort(portId, public.tpe))
                .getOrElse(
                  fail(s"probe ${declared.node.id.show} selects ${portId.show}, which is not public", declared.loc)
                )
              Vector(
                new ResolvedProbe(
                  declared.node.asInstanceOf[ProbeNode[Any]],
                  selected.parameters,
                  port,
                  selected.implementation
                )
              )
        }
      }
    )
    val catalog = imported.fold(local)(_.combined(local))

    val observations = generators.zip(spec.generatorModules).flatMap { (module, g) =>
      val inputs = g.definition.asInstanceOf[GeneratorDefinition[Any]].observations(module.fullParam)
      Option.when(inputs.sources.nonEmpty)(module -> inputs)
    }
    report(
      "observations",
      observations.flatMap { (module, inputs) =>
        val loc   = spec.generatorModule(module.module).get.loc
        val taken = module.view.nodes.map(_.node.name) ++ module.probeDeclaration.ports.map(_.name)
        catalog
          .foreign(inputs)
          .map(p =>
            s"${module.module.show} observes ${p.id.show}, which is not in its design's catalog, at ${at(loc)}"
          ) ++
          inputs.sources
            .filter(p => taken.contains(p.portName))
            .map(p =>
              s"${module.module.show} observes into '${p.portName}', the name of one of its ports, at ${at(loc)}"
            )
      }
    )
    (generators, catalog, VectorMap.from(observations.map((module, inputs) => module.module -> inputs)))
