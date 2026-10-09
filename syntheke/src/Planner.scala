package me.jiuyang.syntheke

private[syntheke] object Planner:
  def plan(
    spec: DesignSpec,
    edges: Vector[ResolvedEdge],
    probes: ProbeCatalog,
    observations: Map[ModuleId, ProbeBindings]
  ): (Vector[PortPlan], Vector[WirePlan], Map[ModuleId, LayerTree]) =
    def ancestors(endpoint: ModuleId): Vector[ModuleId] =
      Iterator.iterate(endpoint.parent)(_.flatMap(_.parent)).takeWhile(_.isDefined).flatten.toVector

    def chain(endpoint: ModuleId, port: PortName, base: PortName, stop: Option[ModuleId],
      direction: PortDirection, interface: ProtocolInterface, kind: WireKind,
      loc: SourceLoc): (Vector[PortPlan], Vector[WirePlan], LocalEndpoint) =
      val modules = ancestors(endpoint).takeWhile(m => !stop.contains(m) && spec.root.isAncestorOf(m))
      val ports = modules.map(m => PortPlan(m, direction, PortName.dangle(m, endpoint, base), interface, loc))
      val wires = modules.zipWithIndex.map { (m, i) =>
        val child =
          if i == 0 then LocalEndpoint.ChildPort(endpoint.path.last, port)
          else LocalEndpoint.ChildPort(modules(i - 1).path.last, ports(i - 1).name)
        val here = LocalEndpoint.ThisPort(ports(i).name)
        val (from, to) = if direction == PortDirection.Output then (child, here) else (here, child)
        WirePlan(m, from, to, kind, loc)
      }
      val end =
        if stop.isEmpty && modules.nonEmpty then LocalEndpoint.ThisPort(ports.last.name)
        else if modules.isEmpty then LocalEndpoint.ChildPort(endpoint.path.last, port)
        else LocalEndpoint.ChildPort(modules.last.path.last, ports.last.name)
      (ports, wires, end)

    def endpoint(node: ModuleNodeId): ModuleNodeId = spec.modules(node.module) match
      case boundary: BoundaryModuleSpec => ModuleNodeId(boundary.target, node.name)
      case _ => node

    val declOf = spec.binds.map(b => b.id -> b).toMap
    val functional = edges.map { edge =>
      val decl = declOf(edge.bind)
      val source = endpoint(edge.bind.source)
      val target = endpoint(edge.bind.target)
      val terminal = Vector(edge.bind.source, edge.bind.target).flatMap { node =>
        spec.modules(node.module) match
          case b: BoundaryModuleSpec if !b.imported => Some(node -> b)
          case _ => None
      }.headOption
      terminal match
        case Some((node, boundary)) =>
          val output = node == edge.bind.target
          val inner = if output then source else target
          val direction = if output then PortDirection.Output else PortDirection.Input
          val (ports, wires, end) = chain(inner.module, PortName.literal(inner.name),
            PortName(Vector("node", inner.name, if output then "out" else "in")), Some(boundary.target),
            direction, edge.interface, WireKind.Connect, decl.loc)
          val name = PortName.literal(node.name)
          val here = LocalEndpoint.ThisPort(name)
          val wire = if output then WirePlan(boundary.target, end, here, WireKind.Connect, decl.loc)
            else WirePlan(boundary.target, here, end, WireKind.Connect, decl.loc)
          (ports :+ PortPlan(boundary.target, direction, name, edge.interface, decl.loc), wires :+ wire)
        case None =>
          val common = if source.module == target.module then source.module.parent.get else ModuleId.lca(source.module, target.module)
          val (sp, sw, se) = chain(source.module, PortName.literal(source.name), PortName(Vector("node", source.name, "out")),
            Some(common), PortDirection.Output, edge.interface, WireKind.Connect, decl.loc)
          val (tp, tw, te) = chain(target.module, PortName.literal(target.name), PortName(Vector("node", target.name, "in")),
            Some(common), PortDirection.Input, edge.interface, WireKind.Connect, decl.loc)
          (sp ++ tp, sw ++ tw :+ WirePlan(common, se, te, WireKind.Connect, decl.loc))
    }

    val uses = observations.toVector.sortBy(_._1.show).flatMap { (consumer, bindings) =>
      bindings.sources.zip(bindings.ports).map { (source, binding) => (source, Some(consumer -> binding.portName)) }
    }
    val routed: Vector[(ResolvedPublicPort, Option[(ModuleId, String)])] =
      uses ++ probes.ports.map(_ -> None)
    val verification = routed.map { (source, consumer) =>
      val loc = spec.modules(source.id.module).loc
      val common = consumer.map { (target, _) =>
        if source.id.module == target then target.parent.get else ModuleId.lca(source.id.module, target)
      }
      val (sp, sw, se) = chain(source.id.module, PortName.literal(source.id.name), PortName.probeBase(source.id.name),
        common, PortDirection.Output, source.reference, WireKind.DefineReference, loc)
      consumer match
        case Some((target, name)) =>
          val (tp, tw, te) = chain(target, PortName.literal(name), PortName(Vector("observation", name)),
            common, PortDirection.Input, source.reference.inner, WireKind.Connect, loc)
          val read = WirePlan(common.get, se, te, WireKind.ReadReference, loc)
          (sp ++ tp, sw ++ tw :+ read, (sp.map(_.module) :+ common.get).flatMap(m => source.reference.layer.map(m -> _)))
        case None =>
          (sp, sw, sp.flatMap(p => source.reference.layer.map(p.module -> _)))
    }
    val ports = (functional.flatMap(_._1) ++ verification.flatMap(_._1)).distinct
    val wires = (functional.flatMap(_._2) ++ verification.flatMap(_._2)).distinct
    val layers = verification.flatMap(_._3).foldLeft(Map.empty[ModuleId, LayerTree]) { case (acc, (module, path)) =>
      acc.updated(module, acc.getOrElse(module, LayerTree.empty).add(path))
    }
    (ports, wires, layers)
