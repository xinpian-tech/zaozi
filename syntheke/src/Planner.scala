// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Cross-hierarchy port and wire planning plus FIRRTL layer declarations (doc @ch-hierarchy).
  *
  * A settled design edge has two generator-module endpoints; its wiring scope `W` is their lowest common ancestor (the
  * shared parent when both endpoints live in one module). Dangle ports are generated on every wrapper boundary strictly
  * between an endpoint's parent and `W`, and bundle-level wires are planned per wrapper.
  *
  * A probe source has no in-design consumer: its branch runs from the source's parent up to and including the root, so
  * the root's dangle ports are the design's top-level probe ports.
  */
private[syntheke] object Planner:

  def plan(
    spec:  DesignSpec,
    edges: Vector[ResolvedEdge]
  ): (Vector[PortPlan], Vector[WirePlan], Map[ModuleId, LayerTree]) =

    /** Wrapper modules strictly between `endpoint`'s parent and `w`, closest to the endpoint first. */
    def branchModules(endpoint: ModuleId, w: ModuleId): Vector[ModuleId] =
      Iterator
        .iterate(endpoint.parent)(_.flatMap(_.parent))
        .takeWhile(m => m.exists(_ != w))
        .flatten
        .toVector

    /** Every strict ancestor of `endpoint`: its parent first, the root last. */
    def ancestors(endpoint: ModuleId): Vector[ModuleId] =
      Iterator.iterate(endpoint.parent)(_.flatMap(_.parent)).takeWhile(_.isDefined).flatten.toVector

    /** Dangle-port name on wrapper `m` for the connection ending at `endpoint`'s port with `base` segments. */
    def dangleName(m: ModuleId, endpoint: ModuleId, base: PortName): PortName =
      val rel = endpoint.path.drop(m.path.length)
      PortName(rel.flatMap(inst => Vector("inst", inst))) ++ base

    /** Dangle ports on the wrappers `ms` and the wire chain through them. */
    def planChain(
      endpoint:  ModuleId,         // generator module owning the endpoint port
      portName:  PortName,         // the generator's own port name
      base:      PortName,         // dangle base segments for this endpoint
      ms:        Vector[ModuleId], // wrappers hosting dangle ports, closest to the endpoint first
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

    /** The chain's end as the module above `ms` sees it: the child-port reference the wiring scope connects. */
    def chainEnd(endpoint: ModuleId, portName: PortName, base: PortName, ms: Vector[ModuleId]): LocalEndpoint =
      if ms.isEmpty then LocalEndpoint.ChildPort(endpoint.path.last, portName)
      else LocalEndpoint.ChildPort(ms.last.path.last, dangleName(ms.last, endpoint, base))

    // ============ design edges ============
    val designParts = edges.map { e =>
      val decl                 = spec.binds(e.bind.order)
      val a                    = e.bind.source.module
      val b                    = e.bind.target.module
      val w                    = if a == b then a.parent.get else ModuleId.lca(a, b)
      val origin               = PlanOrigin.Design(e.bind)
      val srcName              = PortName(e.bind.source.name)
      val srcBase              = PortName("node", e.bind.source.name, "out")
      val srcMs                = branchModules(a, w)
      val tgtName              = PortName(e.bind.target.name)
      val tgtBase              = PortName("node", e.bind.target.name, "in")
      val tgtMs                = branchModules(b, w)
      val (srcPorts, srcWires) =
        planChain(a, srcName, srcBase, srcMs, PortDirection.Output, e.interface, origin, decl.loc)
      val (tgtPorts, tgtWires) =
        planChain(b, tgtName, tgtBase, tgtMs, PortDirection.Input, e.interface, origin, decl.loc)
      val lcaWire              =
        WirePlan(w, chainEnd(a, srcName, srcBase, srcMs), chainEnd(b, tgtName, tgtBase, tgtMs), origin, decl.loc)
      (srcPorts ++ tgtPorts, srcWires ++ tgtWires :+ lcaWire)
    }

    // ============ probe sources ============
    // One pure-probe dangle port and define chain per signal leaf of every source interface, on every wrapper from
    // the source's parent up to and including the root: probes never form aggregates in hardware, so Vec leaves route
    // like any other and no open aggregate types are needed. The root's ports are the top-level probe ports.
    val dvParts = for
      g                <- spec.generatorModules
      s                <- g.dvSources
      (leafPath, leaf) <- ProtocolBundle.leaves(s.interface)
    yield
      val origin         = PlanOrigin.Verification(DVSourceId(g.id, s.name))
      val ms             = ancestors(g.id)
      val (ports, wires) = planChain(
        endpoint = g.id,
        portName = PortName(s.name +: leafPath.nameSegments),
        base = PortName("dv-source" +: s.name +: leafPath.nameSegments :+ "out"),
        ms = ms,
        direction = PortDirection.Output,
        interface = leaf,
        origin = origin,
        loc = s.loc
      )
      // Layer declarations on every wrapper hosting a colored probe port.
      (ports, wires, ms.map(_ -> s.layer))

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
