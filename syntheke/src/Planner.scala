// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Cross-hierarchy port and wire planning plus FIRRTL layer declarations (doc @ch-hierarchy).
  *
  * Every settled connection has two generator-module endpoints. The wiring scope `W` is their lowest common ancestor
  * (the shared parent when both endpoints live in one module); dangle ports are generated on every wrapper boundary
  * strictly between an endpoint's parent and `W`, and bundle-level wires are planned per wrapper.
  */
private[syntheke] object Planner:

  /** One planned branch: its dangle ports, the wires inside those wrappers, and the end reference inside `W`. */
  private final case class Branch(ports: Vector[PortPlan], wires: Vector[WirePlan], end: LocalEndpoint)

  def plan(
    spec:    DesignSpec,
    settled: Negotiator.Settled
  ): (Vector[PortPlan], Vector[WirePlan], Map[ModuleId, LayerTree]) =

    /** Wrapper modules strictly between `endpoint`'s parent and `w`, closest to the endpoint first. */
    def branchModules(endpoint: ModuleId, w: ModuleId): Vector[ModuleId] =
      Iterator
        .iterate(endpoint.parent)(_.flatMap(_.parent))
        .takeWhile(m => m.exists(_ != w))
        .flatten
        .toVector

    /** Dangle-port name on wrapper `m` for the connection ending at `endpoint`'s port with `base` segments. */
    def dangleName(m: ModuleId, endpoint: ModuleId, base: PortName): PortName =
      val rel = endpoint.path.drop(m.path.length)
      PortName(rel.flatMap(inst => Vector("inst", inst))) ++ base

    def planBranch(
      endpoint:  ModuleId, // generator module owning the endpoint port
      portName:  PortName, // the generator's own port name
      base:      PortName, // dangle base segments for this endpoint
      w:         ModuleId,
      direction: PortDirection,
      interface: ProtocolInterface,
      origin:    PlanOrigin,
      loc:       (sourcecode.File, sourcecode.Line)
    ): Branch =
      val ms    = branchModules(endpoint, w)
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
      val end   =
        if ms.isEmpty then LocalEndpoint.ChildPort(endpoint.path.last, portName)
        else LocalEndpoint.ChildPort(ms.last.path.last, dangleName(ms.last, endpoint, base))
      Branch(ports, wires, end)

    // ============ design edges ============
    val designParts = settled.edges.map { e =>
      val decl   = spec.binds(e.bind.order)
      val a      = e.bind.source.module
      val b      = e.bind.target.module
      val w      = if a == b then a.parent.get else ModuleId.lca(a, b)
      val origin = PlanOrigin.Design(e.bind)
      val src    = planBranch(
        endpoint = a,
        portName = PortName(e.bind.source.name),
        base = PortName("node", e.bind.source.name, "out"),
        w = w,
        direction = PortDirection.Output,
        interface = e.interface,
        origin = origin,
        loc = decl.loc
      )
      val tgt    = planBranch(
        endpoint = b,
        portName = PortName(e.bind.target.name),
        base = PortName("node", e.bind.target.name, "in"),
        w = w,
        direction = PortDirection.Input,
        interface = e.interface,
        origin = origin,
        loc = decl.loc
      )
      (src.ports ++ tgt.ports, src.wires ++ tgt.wires :+ WirePlan(w, src.end, tgt.end, origin, decl.loc))
    }

    // ============ verification binds ============
    // One pure-probe dangle port and wire chain per signal leaf of the source interface: probes never form
    // aggregates in hardware, so Vec leaves route like any other and no open aggregate types are needed.
    val dvParts = for
      group            <- settled.dvGroups
      (bindId, i)      <- group.binds.zipWithIndex
      (leafPath, leaf) <- ProtocolBundle.leaves(group.interfaces.sources(i))
    yield
      val sinkModule = group.sink.module
      val w          = sinkModule.parent.get
      val decl       = spec.dvBinds.find(_.bindId == bindId).get
      val source     = bindId.source
      val origin     = PlanOrigin.Verification(bindId)
      val src        = planBranch(
        endpoint = source.module,
        portName = PortName(source.name +: leafPath.nameSegments),
        base = PortName("dv-source" +: source.name +: leafPath.nameSegments :+ "out"),
        w = w,
        direction = PortDirection.Output,
        interface = leaf,
        origin = origin,
        loc = decl.loc
      )
      val sinkRef    = LocalEndpoint.ChildPort(
        sinkModule.path.last,
        PortName(group.sink.name),
        group.interfaces.sinkPaths(i) ++ leafPath
      )
      // Layer declarations on every wrapper the probe crosses, including the wiring scope.
      val layered    = (branchModules(source.module, w) :+ w).map(_ -> group.layers(i))
      (src.ports, src.wires :+ WirePlan(w, src.end, sinkRef, origin, decl.loc), layered)

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
