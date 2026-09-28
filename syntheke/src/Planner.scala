package me.jiuyang.syntheke

private[syntheke] object Planner:

  def plan(
    spec:         DesignSpec,
    edges:        Vector[ResolvedEdge],
    probes:       ProbeCatalog,
    observations: ProbeBindings
  ): (Vector[PortPlan], Vector[WirePlan], Map[ModuleId, LayerTree]) =

    def ancestors(endpoint: ModuleId): Vector[ModuleId] =
      Iterator.iterate(endpoint.parent)(_.flatMap(_.parent)).takeWhile(_.isDefined).flatten.toVector

    import PortName.dangle as dangleName

    def planChain(
      endpoint:  ModuleId,
      portName:  PortName,
      base:      PortName,
      ms:        Vector[ModuleId],
      direction: PortDirection,
      interface: ProtocolInterface,
      origin:    PlanOrigin,
      loc:       (sourcecode.File, sourcecode.Line)
    ): (Vector[PortPlan], Vector[WirePlan]) =
      val ports = ms.map(m => PortPlan(m, direction, dangleName(m, endpoint, base), interface, origin, loc))
      val wires = ms.zipWithIndex.map { (m, i) =>
        val name       = dangleName(m, endpoint, base)
        val childRef   =
          if i == 0 then LocalEndpoint.ChildPort(endpoint.path.last, portName)
          else LocalEndpoint.ChildPort(ms(i - 1).path.last, dangleName(ms(i - 1), endpoint, base))
        val thisRef    = LocalEndpoint.ThisPort(name)
        val (from, to) = direction match
          case PortDirection.Output => (childRef, thisRef)
          case PortDirection.Input  => (thisRef, childRef)
        WirePlan(m, from, to, origin, loc)
      }
      (ports, wires)

    def chainEnd(endpoint: ModuleId, portName: PortName, base: PortName, ms: Vector[ModuleId]): LocalEndpoint =
      if ms.isEmpty then LocalEndpoint.ChildPort(endpoint.path.last, portName)
      else LocalEndpoint.ChildPort(ms.last.path.last, dangleName(ms.last, endpoint, base))

    val designParts = edges.map { e =>
      val decl                 = spec.binds(e.bind.order)
      val a                    = e.bind.source.module
      val b                    = e.bind.target.module
      val w                    = if a == b then a.parent.get else ModuleId.lca(a, b)
      val origin               = PlanOrigin.Design(e.bind)
      val srcName              = PortName(Vector(e.bind.source.name))
      val srcBase              = PortName(Vector("node", e.bind.source.name, "out"))
      val srcMs                = ancestors(a).takeWhile(_ != w)
      val tgtName              = PortName(Vector(e.bind.target.name))
      val tgtBase              = PortName(Vector("node", e.bind.target.name, "in"))
      val tgtMs                = ancestors(b).takeWhile(_ != w)
      val (srcPorts, srcWires) =
        planChain(a, srcName, srcBase, srcMs, PortDirection.Output, e.interface, origin, decl.loc)
      val (tgtPorts, tgtWires) =
        planChain(b, tgtName, tgtBase, tgtMs, PortDirection.Input, e.interface, origin, decl.loc)
      val lcaWire              =
        WirePlan(w, chainEnd(a, srcName, srcBase, srcMs), chainEnd(b, tgtName, tgtBase, tgtMs), origin, decl.loc)
      (srcPorts ++ tgtPorts, srcWires ++ tgtWires :+ lcaWire)
    }

    val routed  = if spec.testbench.nonEmpty then observations.nodes else probes.mappedPorts
    val inputOf = observations.ports.map(input => input.source -> input.portName).toMap
    val dvParts = routed.map { source =>
      val g        = spec.generatorModule(source.id.module).get
      val origin   = PlanOrigin.Verification(source.id)
      val portName = PortName.literal(source.id.name)
      val base     = PortName.probeBase(source.id.name)
      val leaf     = source.reference
      spec.testbench match
        case Some(tb) =>
          val ms             = ancestors(g.id).takeWhile(_ != ModuleId.root)
          val (ports, wires) = planChain(g.id, portName, base, ms, PortDirection.Output, leaf, origin, g.loc)
          val tbWire         = WirePlan(
            ModuleId.root,
            chainEnd(g.id, portName, base, ms),
            LocalEndpoint.ChildPort(tb.path.last, PortName.literal(inputOf(source.id))),
            origin,
            g.loc
          )
          (ports, wires :+ tbWire, (ms :+ ModuleId.root).flatMap(m => source.reference.layer.map(m -> _)))
        case None     =>
          val ms             = ancestors(g.id)
          val (ports, wires) = planChain(g.id, portName, base, ms, PortDirection.Output, leaf, origin, g.loc)
          (ports, wires, ms.flatMap(m => source.reference.layer.map(m -> _)))
    }

    val layers = dvParts
      .flatMap(_._3)
      .foldLeft(Map.empty[ModuleId, LayerTree]) { case (acc, (m, lp)) =>
        acc.updated(m, acc.getOrElse(m, LayerTree.empty).add(lp))
      }

    (
      designParts.flatMap(_._1) ++ dvParts.flatMap(_._1),
      designParts.flatMap(_._2) ++ dvParts.flatMap(_._2),
      layers
    )
